# SignalDock Android SDK: vendor transport and retry research

Research reference date: 2026-10-09, Asia/Seoul
Scope: Default event upload paths in Segment analytics-kotlin, Amplitude Android-Kotlin/analytics-core, and Mixpanel Android. Official code and documentation were used.
Method: Static code inspection at pinned commits. No tests, builds, implementation, or installation were performed. Review and correction of overstatements in the local spec are separate main-agent work.
Model: The main agent delegated research by specifying `model: gpt-6.1-sol` when creating the subagent. The main agent rechecked Segment, Amplitude, and Mixpanel transport code and pinned OkHttp EventListener source. Billing cost was not measured separately.

## Conclusion

All three examples have SDK-managed queues and retry policies. Describing the whole proposal as common industry practice would overstate the evidence.

- Default transports differ. Segment uses an OkHttp 4.12.0 adapter; Amplitude and Mixpanel use `HttpURLConnection`. [S1][S2][S3][A1][M1]
- SDK backoff configuration and persistence also differ. Segment smart retry is disabled by default. The inspected Amplitude and Mixpanel backoff counters are in memory. A persistent queue and a persistent retry deadline are separate features. [S4][S6][S7][A4][M8]
- `attemptId + EventListener` is an optional SignalDock diagnostics design. No evidence was found that the three SDKs provide this combination by default in the inspected paths. The file scope is stated below.
- `retryOnConnectionFailure(true)` can coexist with SDK backoff. Describing this as "OkHttp only recovers connections and the sender performs all HTTP retries" is incorrect. There are 408, 503, and 421 follow-up exceptions. [O6][O7]

## Pinned code baseline

The latest default-branch commit before the reference date was pinned for each repository. This does not establish equivalence to each vendor's latest distributed artifact. For Mixpanel, the repository-wide commit was used, but comparison was limited to `analytics/`.

| Target | Commit | Commit time, UTC |
| --- | --- | --- |
| Segment analytics-kotlin | `ae263605ab96a9bc874547d800f44400fe02762e` | 2026-09-09 20:49:18 |
| Amplitude-Kotlin, Android and core | `2a187d82e9e779ab7e2668541cbc2506470cf9a5` | 2026-10-08 00:02:46 |
| Mixpanel Android | `37157c2f9345e38dba3a904aad350ee4091169ea` | 2026-10-02 18:27:36 |

OkHttp release-tag commits were checked separately.

| Release | Commit | Purpose |
| --- | --- | --- |
| 4.12.0 | `4984568367caaf359b82c452bd28b5e192824d1c` | Segment dependency version and existing EventListener API |
| 5.0.0 | `1b703d52a988222c4899c0bdf233db30f4f974c9` | Whether decision callbacks are included in a stable release |
| 5.5.0 | `a94bdf152084d11acecd44dcd09ffef203f4f0aa` | Callbacks and follow-up conditions in the current stable release |

During retrieval, `square/okhttp` redirected to `lysine-dev/okhttp`. The GitHub API pointed to the same repository, so OkHttp links use the destination repository. At research time, the [project documentation](https://lysine.dev/okhttp/) showed 5.5.0; the pinned changelog gives its release date as 2026-08-16. [O9]

## Comparison of the three SDKs

| Item | Segment analytics-kotlin | Amplitude Android-Kotlin/core | Mixpanel Android |
| --- | --- | --- | --- |
| Actual default transport | RequestFactory → OkHttpURLConnection → OkHttp Call | core HttpClient → URL.openConnection | AnalyticsMessages → HttpService → URL.openConnection |
| Queue/retry owner | EventPipeline + RetryStateMachine + storage | EventPipeline + response handler + storage | AnalyticsMessages worker + MPDbAdapter |
| SDK backoff | Exponential + jitter with smart retry enabled. Legacy by default | Base delays of 1, 8, 32, 128, 300 seconds, ±50% jitter, then repeated 300-second base | 60 seconds × 2^failure, capped at 10 minutes after comparison with Retry-After |
| Internal transport retry | Inherits OkHttp defaults. Can change with an injected client | No explicit retry loop or OkHttp setting in the inspected default HttpClient | Explicit three-iteration loop, failover to configured backup host |
| Observed diagnostics | Logs, error reporting, X-Retry-Count | Logs, event outcomes, diagnostics metadata, pending upload recovery marker | Logs + NetworkErrorListener |
| Dedicated local attemptId + EventListener | Not found in inspected default paths | Not found in inspected default paths | Not found in inspected default paths |

OS-specific recovery inside `HttpURLConnection` is outside this research scope. Do not interpret the findings as proof of no internal retry.

### 1. Segment analytics-kotlin

Default transport: `HTTPClient.upload()` delegates to RequestFactory. The default RequestFactory creates an OkHttpClient and returns an `OkHttpURLConnection` adapter. The adapter calls `client.newCall(finalRequest).execute()`. Inferring the default transport from the separate `URL.openConnection()` method remaining in `HTTPClient.kt` can give the wrong result. [S1][S2]

Short source excerpts: `private val okHttpClient = httpClient ?: OkHttpClient.Builder()`; `client.newCall(finalRequest).execute()`. The dependency is `com.squareup.okhttp3:okhttp:4.12.0`. [S1][S2][S3]

Retry ownership: EventPipeline loads retry state and saves it after response handling. With smart retry enabled, RetryStateMachine calculates per-batch failure counts and next retry times using exponential backoff with jitter. Both `RateLimitConfig.enabled` and `BackoffConfig.enabled` default to `false`. Legacy mode retains 429/5xx batches and skips smart backoff calculation. CDN configuration can enable it, so a false default does not mean it is always disabled. [S4][S5][S6][S7][S8][S9]

Short source excerpts: `retryState = storage.loadRetryState()`; `storage.saveRetryState(retryState)`. These are static evidence of a storage path, not runtime verification of crash durability. [S4][S5]

Internal retry settings and diagnostics: RequestFactory's builder does not specify `retryOnConnectionFailure`. The code indicates that it inherits `true` from its OkHttp 4.12.0 dependency. A caller can pass a separate OkHttpClient to the RequestFactory constructor. Distinguish an explicit vendor choice of true from inheritance of a default. [S1][O1]

EventPipeline puts the SDK retry count in the `X-Retry-Count` header and provides logs/error reporting. This is neither an OkHttp internal retry count nor a unique request ID. No SDK-owned `EventListener` installation or dedicated `attemptId` was found in `HTTPClient.kt`, `OkHttpURLConnection.kt`, or `EventPipeline.kt`. An injected client could have its own listener. [S1][S2][S5]

### 2. Amplitude Android-Kotlin/core

Default transport: The default core HttpClient calls `requestedURL.openConnection() as HttpURLConnection`. EventPipeline uses `configuration.httpClient` if present and otherwise creates the default HttpClient. Android Configuration exposes this injection point too. Describing the default transport as OkHttp is incorrect. [A1][A2][A5]

Short source excerpts: `requestedURL.openConnection() as HttpURLConnection`; `?: HttpClient(amplitude.configuration, amplitude.logger),`. [A1][A2]

The [official Android-Kotlin documentation](https://www.amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk) describes default HttpURLConnection and a custom HttpClientInterface. Its custom OkHttp example does not establish the default upload stack.

Retry ownership: Android's default storage provider uses event storage. EventPipeline reads stored event files and schedules retries from response-handler results. The handler releases files on timeout, failure, and 429 so they can be read again. The 429 path delays 30 seconds; other retryable outcomes use a separate backoff handler. [A3][A5][A6][A7]

The current handler uses base delays of `listOf(1_000L, 8_000L, 32_000L, 128_000L, 300_000L)`, applies ±50% jitter, and repeats the last value. `attempt` is an `AtomicInteger(0)` field. The inspected handler has no persistent save/restore path for its counter/deadline. Android event storage therefore does not prove that backoff survives process restart. [A4]

`flushMaxRetries` is deprecated and no longer limits retries. Do not apply the historical five-retry limit to current behavior. [A4][A9]

Internal retry settings and diagnostics: No explicit retry loop or OkHttp retry setting was found in the default `HttpClient.kt` request path. Custom transport internals and Android platform recovery are separate matters. [A1][A2]

Diagnostics include more than logs. EventPipeline uses a pending upload marker to detect unfinished uploads at the next initialization and includes diagnostics in uploads. AnalyticsRequest serializes these as `request_metadata.sdk`. Do not interpret the marker or metadata as a wire retry count or unique attemptId. No default `EventListener` installation or dedicated request/attempt ID was found in `HttpClient.kt`, `EventPipeline.kt`, or `AnalyticsRequest.kt`. [A3][A8]

### 3. Mixpanel Android

Default transport: AnalyticsMessages uses HttpService as its default poster. HttpService calls `connection = (HttpURLConnection) url.openConnection()`. The queue uses MPDbAdapter's SQLite event table. [M1][M2][M7]

Retry ownership has two layers. HttpService contains a `while (retries < 3)` loop. After a primary request fails, it also tries the configured backup host. Client errors stop immediately. Under the actual loop conditions, delays are 100ms and 200ms; the 300ms mentioned in the comment does not execute. With a backup configured and every request failing, one upper-level call can make up to six explicit host requests. This count follows from the code path and excludes platform internals. [M4][M9]

Above that layer, AnalyticsMessages retains the queue after IOException/ServiceUnavailableException and reschedules through a Handler message. It takes the larger of `2^mFailedRetries * 60000` and Retry-After, then caps it at 10 minutes. `mFailedRetries` and `mTrackEngageRetryAfter` are worker fields. No persistent save/restore of this state was found in the inspected sender path. [M3][M8]

Internal retry settings and diagnostics: The inspected HttpService's three-iteration limit and short delays are hard-coded. The backup host is configurable, but that is not a general retry-count setting. [M4][M9]

Mixpanel provides more than logs. `MixpanelNetworkErrorListener` reports endpoint, diagnostic DNS lookup IP, duration, body size, response code, and exception. The IP comes from a separate DNS lookup and is not guaranteed to match the actual connection peer. No OkHttp EventListener or dedicated attemptId was found in `HttpService.java`, `AnalyticsMessages.java`, or the listener interface. Do not automatically count listener error notifications as retries. [M1][M5][M6]

## OkHttp observation APIs: use only what is needed

The base EventListener is a stable public API. The [official Events documentation](https://lysine.dev/okhttp/features/events/) says it has been public since 3.11. Call lifecycle and connect/request/response events were also checked in 4.12.0 code. Retries and follow-ups can occur within the same Call. [O2][O3]

Callback counts are not retry counts.

- `callStart` occurs once per Call, not once per internal retry.
- `connectFailed` indicates a failed connection attempt. It does not mean an HTTP request was sent.
- Events of the same type can repeat for redirects, authentication follow-ups, or attempts on another route.
- DNS/connect events can be skipped when a connection is reused.

Do not label `connectStart - 1`, `requestHeadersStart - 1`, or the sum of `connectFailed` events as the internal retry count. [O2][O3]

Consider version-specific APIs only when exact decisions are needed. The 5.0.0-alpha.15 changelog records the addition of `retryDecision(call, exception, retry)` and `followUpDecision(call, networkResponse, nextRequest)`. Both are public EventListener methods in 5.0.0 stable and 5.5.0 stable, without experimental annotations on those declarations. The 4.12.0 EventListener file has neither method. [O4][O5][O8]

`retryDecision` observes a retry decision after a connectivity failure. HTTP-status follow-ups belong to `followUpDecision`. `retry == true` and `nextRequest != null` are decisions, not proof of successful server receipt. These callbacks are not configuration APIs that veto policy. The first release does not need a version change or detailed collection just to use them. [O4][O5]

## 408, 503, and 421: paths that bypass sender backoff

The following behavior was checked in both pinned OkHttp 4.12.0 and 5.5.0. Not all 5xx responses are automatically retried. [O6][O7]

| Response | Follow-up conditions within the same Call | Effect on sender |
| --- | --- | --- |
| 408 | Recovery setting true, replayable body, immediately previous response is not 408, Retry-After does not block immediate retry. Default is 0 when the header is absent | Can resend before the sender receives the first 408 |
| 503 | Immediately previous response is not 503; `Retry-After: 0`. An absent header does not meet the immediate retry condition | Can resend before the sender applies 5xx backoff |
| 421 | Replayable body; response received on a coalesced HTTP/2 connection | Can resend on another connection |

Short source excerpts: 408: `if (!chain.retryOnConnectionFailure)`; 503: `retryAfter(userResponse, Integer.MAX_VALUE) == 0`; 421: `!exchange.isCoalescedConnection`. [O6]

The 503 and 421 branches do not check retryOnConnectionFailure. Setting it to `false` alone therefore does not suppress every HTTP follow-up. The shared loop applies other limits, including one-shot bodies. [O6][O7][O10]

Transport-internal retries can occur between the SDK's persistent next-attempt checks. A server's intermediate response does not guarantee that the sender's cooldown policy will run.

## Minimum SignalDock recommendations

Adopted: The SDK sender manages the queue and next transmission time. Persist retry state to preserve cooldown after process restart. This is a choice for SignalDock's contract, not a claim that all three vendors use the same persistent backoff.

OkHttp: `retryOnConnectionFailure(true)` is a reasonable choice for retaining default recovery. Describe its scope as recovery within one Call and limited HTTP follow-ups. The SDK sender applies persistent backoff based on the final result returned by the transport.

Minimum diagnostics: Start with transmission start, final status/exception, elapsed time, and next transmission time. If correlation is needed, create a local `attemptId` for each new sender Call and retain it during that Call's internal recovery. Keep it separate from event IDs; server deduplication uses the existing event ID. Do not require a new request header.

Deferred: Add detailed EventListener timelines and 5.x decision callbacks when failure analysis needs them. They are not required to match common vendor practice. Starting with logs is consistent with the findings.

Policy boundary: The current proposal cannot guarantee that the sender decides cooldown first for every 408, 503, and 421. For the first release, explicitly allowing limited internal follow-ups is simpler. When the server requires cooldown, do not send `Retry-After: 0` with 503. This alone does not control proxy responses or all follow-ups. Do not add custom retry interceptors or dependencies on internal classes before strict suppression is required.

Recommended wording:

> SignalDock manages its persistent queue and retry times in the SDK sender. It allows OkHttp's default recovery and limited HTTP follow-ups. Diagnostics start with final-result logs. Add local attemptId and EventListener support when needed. This project design draws on vendor examples; it does not replicate an implementation shared by all three SDKs.

## Evidence scope and interpretation

"Not found" refers only to the transport/sender files named for each item. It does not establish absence across the whole repository, optional plugins, custom clients, or Android platform implementation. Callback counts were not treated as equivalent to actual retry counts. Statically observed behavior and project recommendations remain separate.

All links below pin a commit and line range. The short source excerpts can be found at those lines.

[S1]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/HTTPClient.kt#L146-L200 "Segment HTTPClient.kt: RequestFactory·default client"
[S2]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/utilities/OkHttpURLConnection.kt#L50-L82 "Segment OkHttpURLConnection.kt: Actual Call"
[S3]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/build.gradle#L38-L42 "Segment core/build.gradle: OkHttp version"
[S4]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/platform/EventPipeline.kt#L66-L81 "Segment EventPipeline.kt: State initialization"
[S5]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/platform/EventPipeline.kt#L221-L287 "Segment EventPipeline.kt: upload·retry header·state save"
[S6]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/retry/RetryConfig.kt#L9-L60 "Segment RetryConfig.kt: Defaults"
[S7]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/retry/RetryStateMachine.kt#L10-L23 "Segment RetryStateMachine.kt: Legacy behavior"
[S8]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/retry/RetryStateMachine.kt#L87-L118 "Segment RetryStateMachine.kt: backoff·jitter"
[S9]: https://github.com/segmentio/analytics-kotlin/blob/ae263605ab96a9bc874547d800f44400fe02762e/core/src/main/java/com/segment/analytics/kotlin/core/platform/plugins/SegmentDestination.kt#L116-L134 "Segment SegmentDestination.kt: CDN config"
[A1]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/utilities/http/HttpClient.kt#L40-L67 "Amplitude HttpClient.kt: Default URLConnection"
[A2]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/platform/EventPipeline.kt#L30-L57 "Amplitude EventPipeline.kt: transport·retry owner"
[A3]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/platform/EventPipeline.kt#L145-L201 "Amplitude EventPipeline.kt: diagnostics·upload·retry"
[A4]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/utilities/ExponentialBackoffRetryHandler.kt#L20-L80 "Amplitude ExponentialBackoffRetryHandler.kt: schedule·In-memory counter"
[A5]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/android/src/main/java/com/amplitude/android/Configuration.kt#L27-L59 "Amplitude Android Configuration.kt: storage·custom client"
[A6]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/android/src/main/java/com/amplitude/android/storage/AndroidStorageContextV3.kt#L26-L43 "Amplitude AndroidStorageContextV3.kt: event storage"
[A7]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/utilities/FileResponseHandler.kt#L137-L172 "Amplitude FileResponseHandler.kt: Failed file release"
[A8]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/utilities/http/AnalyticsRequest.kt#L19-L29 "Amplitude AnalyticsRequest.kt: diagnostics metadata"
[A9]: https://github.com/amplitude/Amplitude-Kotlin/blob/2a187d82e9e779ab7e2668541cbc2506470cf9a5/analytics-core/src/main/java/com/amplitude/core/Configuration.kt#L30-L46 "Amplitude core Configuration.kt: deprecated retry cap"
[M1]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/util/HttpService.java#L398-L430 "Mixpanel HttpService.java: URLConnection·Diagnostic IP"
[M2]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/AnalyticsMessages.java#L209-L223 "Mixpanel AnalyticsMessages.java: default poster"
[M3]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/AnalyticsMessages.java#L646-L726 "Mixpanel AnalyticsMessages.java: queue upload·SDK backoff"
[M4]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/util/HttpService.java#L208-L280 "Mixpanel HttpService.java: transport loop·failover"
[M5]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/util/MixpanelNetworkErrorListener.java#L3-L22 "Mixpanel MixpanelNetworkErrorListener.java: callback"
[M6]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/util/HttpService.java#L627-L663 "Mixpanel HttpService.java: Listener invocation"
[M7]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/MPDbAdapter.java#L74-L79 "Mixpanel MPDbAdapter.java: SQLite event table"
[M8]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/AnalyticsMessages.java#L824-L827 "Mixpanel AnalyticsMessages.java: backoff fields"
[M9]: https://github.com/mixpanel/mixpanel-android/blob/37157c2f9345e38dba3a904aad350ee4091169ea/analytics/src/main/java/com/mixpanel/android/mpmetrics/MixpanelAPI.java#L682-L697 "Mixpanel MixpanelAPI.java: backup host"
[O1]: https://github.com/lysine-dev/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt#L471-L478 "OkHttp 4.12.0 OkHttpClient.kt: recovery default"
[O2]: https://github.com/lysine-dev/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/main/kotlin/okhttp3/EventListener.kt#L51-L67 "OkHttp 4.12.0 EventListener.kt: lifecycle·Repeated events"
[O3]: https://github.com/lysine-dev/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/main/kotlin/okhttp3/EventListener.kt#L194-L219 "OkHttp 4.12.0 EventListener.kt: Connection failure·reuse"
[O4]: https://github.com/lysine-dev/okhttp/blob/1b703d52a988222c4899c0bdf233db30f4f974c9/okhttp/src/commonJvmAndroid/kotlin/okhttp3/EventListener.kt#L457-L511 "OkHttp 5.0.0 EventListener.kt: stable decision callbacks"
[O5]: https://github.com/lysine-dev/okhttp/blob/a94bdf152084d11acecd44dcd09ffef203f4f0aa/okhttp/src/commonJvmAndroid/kotlin/okhttp3/EventListener.kt#L478-L535 "OkHttp 5.5.0 EventListener.kt: decision callbacks"
[O6]: https://github.com/lysine-dev/okhttp/blob/a94bdf152084d11acecd44dcd09ffef203f4f0aa/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#L232-L288 "OkHttp 5.5.0 RetryAndFollowUpInterceptor.kt: 408·503·421"
[O7]: https://github.com/lysine-dev/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#L229-L285 "OkHttp 4.12.0 RetryAndFollowUpInterceptor.kt: 408·503·421"
[O8]: https://github.com/lysine-dev/okhttp/blob/a94bdf152084d11acecd44dcd09ffef203f4f0aa/CHANGELOG.md#L328-L375 "OkHttp 5.5.0 CHANGELOG.md: alpha.15 API addition history"
[O9]: https://github.com/lysine-dev/okhttp/blob/a94bdf152084d11acecd44dcd09ffef203f4f0aa/CHANGELOG.md#L4-L6 "OkHttp 5.5.0 CHANGELOG.md: Release date"
[O10]: https://github.com/lysine-dev/okhttp/blob/a94bdf152084d11acecd44dcd09ffef203f4f0aa/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#L93-L118 "OkHttp 5.5.0 RetryAndFollowUpInterceptor.kt: Shared follow-up limits"
