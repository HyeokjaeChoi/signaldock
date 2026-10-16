# SDK integration guide

[한국어](integration-guide.ko.md)

This guide tells an app developer how to add the SignalDock Android SDK to an app and how to use it.
It adds no new behavior. Each statement names the source it comes from: the [spec](../.scratch/signaldock-portfolio/spec.md) ("spec 3" means section 3), the [architecture](../architecture.md), or an [ADR](adr/).
The SDK public API has KDoc on every public declaration. It agrees with this guide.

The SDK package is `local.signaldock`. The release version is `0.1.0`. The coordinate is `local.signaldock:sdk-android:0.1.0` (spec 8.C, Q126).

## Contents

1. [Install](#1-install)
2. [Initialize](#2-initialize)
3. [Track events](#3-track-events)
4. [Handle every TrackResult](#4-handle-every-trackresult)
5. [Cancellation](#5-cancellation)
6. [Offline, retry, and queue limits](#6-offline-retry-and-queue-limits)
7. [Diagnostics](#7-diagnostics)
8. [Privacy](#8-privacy)
9. [Versions and the contract version](#9-versions-and-the-contract-version)
10. [Troubleshooting](#10-troubleshooting)
11. [What the SDK does not do](#11-what-the-sdk-does-not-do)
12. [Code samples](#12-code-samples)

## 1. Install

The SDK is a local Maven repository folder, packed as a ZIP. It is not on Maven Central (spec 1, spec 6 "Reproduction and deliverables", [ADR-0009](adr/0009-maven-zip-and-consumer-app.md) decision 1).

1. Build the ZIP from the repository root (ADR-0009 decision 2):

   ```sh
   sdk/gradlew -p sdk :sdk-android:signaldockMavenZip
   ```

   The output is `sdk/sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip`.
2. Unzip it into a folder.
3. Add the folder as a repository for the group `local.signaldock` only, and depend on the coordinate (ADR-0009 decision 3).
   The independent consumer app is the worked example: [`settings.gradle.kts`](../compatibility/consumer-android/settings.gradle.kts) and [`app/build.gradle.kts`](../compatibility/consumer-android/app/build.gradle.kts).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("/path/to/unzipped/folder")
            content { includeGroup("local.signaldock") }
        }
    }
}
// app/build.gradle.kts
dependencies { implementation("local.signaldock:sdk-android:0.1.0") }
```

Requirements of the 0.1.0 AAR:

- `compileSdk` 36 or newer and `minSdk` 24 or newer ([ADR-0006](adr/0006-sdk-delivery-decisions.md) decision 1; spec 2).
- Kotlin 2.2 or newer and AGP 9.x. The SDK class metadata is 2.2, so the default built-in Kotlin of AGP 9 works (ADR-0009 decision 4, Q132). An app on AGP 8.x cannot use 0.1.0 (ADR-0009 decision 4).
- The app declares the `INTERNET` permission (the consumer app has it in its [manifest](../compatibility/consumer-android/app/src/main/AndroidManifest.xml); [README](../README.md) "Add the SDK to an app"). The SDK needs no R8 rules of its own (ADR-0009 decision 6).
- Use an `https://` endpoint. The SDK does not change `http://` to `https://`. `initialize` rejects an `http://` endpoint with `InvalidConfiguration`, unless the config has `allowInsecureEndpoint = true` (default `false`, ADR-0035). Set it only for a local server on `http://`. The app must also allow cleartext for that host. The consumer app does this for `10.0.2.2`, `127.0.0.1`, and `localhost` only (ADR-0009 decision 5, Q134).

## 2. Initialize

Call `SignalDock.initialize()` once, from your `Application` (spec 3 "Lifetime and cancellation").
It is a suspend function. It prepares storage off the main thread (architecture "SDK lifecycle and cancellation").
The SDK keeps only the application context (spec 3).

```kotlin
public suspend fun initialize(context: Context, config: SignalDockConfig): InitResult
```

`SignalDockConfig` has `endpoint` and `collectionKey`. It also has optional `logLevel` (default `WARN`), four timeouts, and `allowInsecureEndpoint` (default `false`; see the endpoint rule in section 3). The defaults are 30 seconds for the call and 10 seconds each for connect, read, and write. A timeout must be positive and finite (architecture "Agreed defaults, pending measurement"; spec 8 Q117). The configuration is fixed for the SDK lifetime (spec 3). `toString()` hides the collection key (spec 8 Q115).

```kotlin
// sample: initialize
class ShopApplication : Application() {
    /** The one initialization attempt. Activities await it before they call track(). */
    lateinit var sdkInit: Deferred<InitResult>
        private set

    override fun onCreate() {
        super.onCreate()
        sdkInit = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            SignalDock.initialize(
                this@ShopApplication,
                SignalDockConfig(endpoint = "https://events.example.com", collectionKey = "your-collection-key"),
            )
        }
    }
}

fun describe(result: InitResult): String = when (result) {
    is InitResult.Success -> "ready"
    is InitResult.Failure -> when (val e = result.error) {
        is InitError.InvalidConfiguration -> "fix the configuration: ${e.message}"
        InitError.ConfigurationConflict -> "another configuration is active"
        is InitError.StorageFailure -> "storage failed (${e.message}); call initialize again later"
    }
}
// end sample
```

Rules:

- Call `track()` only on the `SignalDock` instance from `InitResult.Success` (architecture "SDK lifecycle and cancellation"; spec 3).
- Calls with the same configuration share one attempt. A success is reused. A different configuration returns `InitError.ConfigurationConflict` (spec 3; architecture "SDK lifecycle and cancellation").
- A failed attempt is not repeated automatically. Call `initialize()` again to retry. The queue and the installation ID stay (architecture "SDK lifecycle and cancellation"; spec 8 Q112).
- If the caller is cancelled, only its own wait stops. The attempt continues in the SDK process scope. There is no completion guarantee after process death (architecture "SDK lifecycle and cancellation"; spec 8 Q114).
- After the process is recreated, the SDK starts again through your `Application` code and recovers the queue (spec 3).
- The SDK has one instance per process. It has no dependency on Compose or ViewModel (spec 2; architecture "SDK lifecycle and cancellation").

## 3. Track events

`track()` is a suspend function. It stores the event on the device (spec 3 "Collection").

```kotlin
public suspend fun track(name: String, block: EventBuilder.() -> Unit = {}): TrackResult
```

```kotlin
// sample: track
suspend fun onProductViewed(sdk: SignalDock): String {
    val result = sdk.track("product_viewed") {
        property("product_id", "P-001")   // string
        property("quantity", 2)           // number
        property("price", 12.5)           // number
        property("is_test", true)         // boolean
    }
    return when (result) {
        is TrackResult.Success -> "stored on the device: ${result.eventId}" // not server receipt
        is TrackResult.Invalid -> "fix the call: ${result.reason} (${result.field})" // nothing stored
        TrackResult.QueueFull -> "queue is full; the event was dropped" // existing events are kept
        is TrackResult.StorageError -> "storage failed: ${result.message}" // nothing stored
    }
}
// end sample
```

Call `track()` once for each user action. Do not collect again automatically after a recomposition or a cancellation (spec 6 "Demo and diagnostics"; architecture "Components and responsibilities").
Your app chooses the event names and property keys. The SDK has no shopping-specific model (spec 3).

### Name and property rules

The SDK does not trim, normalize, or convert input. It returns `TrackResult.Invalid` (spec 3 "Collection"; spec 8 Q64, Q84, Q85).

| Rule | `InvalidReason` | Source |
| --- | --- | --- |
| A name or a property key is not empty and not only whitespace | `EMPTY_NAME` | spec 8 Q64 |
| A name or a property key has no leading or trailing whitespace. Whitespace inside is allowed. Korean text is allowed | `LEADING_OR_TRAILING_WHITESPACE` | spec 8 Q64 |
| Text (names, keys, string values) has no NUL character and no lone surrogate | `FORBIDDEN_CHARACTER` | spec 8 Q85 |
| A property key is used once. A second use fails the whole call, also with the same value | `DUPLICATE_KEY` | spec 8 Q63 |
| A decimal is a finite number. NaN and infinity are not allowed | `NON_FINITE_NUMBER` | spec 3 |
| An integer is within plus or minus (2^53 - 1). This also applies to a whole number passed as `Double` | `NUMBER_OUT_OF_RANGE` | spec 3; architecture "Contracts, generated sources, and JSON" |
| One event is at most 16 KiB of transport JSON, with the common fields | `EVENT_TOO_LARGE` | spec 4 |

More rules:

- Properties are flat. The value is a string, a number, or a boolean. There are no nested objects, arrays, or explicit null. Leave out the key for a missing value (spec 3).
- The SDK has `property` overloads for `String`, `Boolean`, `Int`, `Long`, and `Double`. It has no `Float` overload, because widening a `Float` changes the value ([ADR-0003](adr/0003-sdk-storage-decisions.md) decision 5).
- Send a large ID as a string. Send an exact amount as an integer in the smallest currency unit, within the allowed range (spec 3).
- The whitespace set is a fixed list, the same as in the server. `Char.isWhitespace()` is not the rule (ADR-0003 decision 4).
- Text is compared exactly, without Unicode normalization. Two names that look the same can be different names (spec 8 Q84).
- The SDK adds `eventId` (a UUIDv4), `installationId`, `occurredAt` (device time when `track()` handles the call), and `sdkVersion`. Your app cannot set `eventId` or `occurredAt` (spec 3 "Common information").

### Optional: tracking plan and generated functions

You can describe your events in a plan file and generate typed functions from it. This is optional. The SDK and its public API do not change, and plain `track("name") { property(...) }` keeps working (spec Q153).

This repository shows the pattern in `tracking-plan/` ([ADR-0021](adr/0021-tracking-plan-codegen.md)). `tracking-plan/plan.json` lists each event with its properties, their types, and whether each is required. `node tracking-plan/generate.mjs` writes a Kotlin file and a TypeScript file. The generated Kotlin function only calls `track()`: a required property is a non-null parameter, and an optional property is a nullable parameter that is left out when it is `null`.

```kotlin
// Generated from tracking-plan/plan.json (demo app). Not part of the SDK.
sdk.productViewed(productId = "P-001")                      // price_minor left out
sdk.productViewed(productId = "P-001", priceMinor = 59_000)

// The same event with plain track():
sdk.track("product_viewed") { property("product_id", "P-001") }
```

The TypeScript output has types and builder functions that return `{ name, properties }`. The SDK does not check the plan. The server can: when it has a plan (`SIGNALDOCK_TRACKING_PLAN`, set by Compose), it marks each stored event `planned`, `unplanned_event`, or `invalid_properties`. It never rejects or drops an event for plan reasons, and the ACK does not change (spec Q155, [ADR-0022](adr/0022-server-tracking-plan-status.md)). The Dashboard shows the status and the violation counts.

## 4. Handle every TrackResult

| Result | Meaning | What your app does | Source |
| --- | --- | --- | --- |
| `Success(eventId)` | The event is stored on the device. This is not server receipt | Nothing. The SDK sends it later | spec 3; architecture "Commit boundaries" |
| `Invalid(reason, field)` | The input broke a rule. Nothing is stored. `field` is `"name"` or the property key. It is `null` for `EVENT_TOO_LARGE` | Fix the call in your code. Do not retry the same input | spec 3; spec 8 Q63 |
| `QueueFull` | The queue is at 10,000 events or 16 MiB. The SDK keeps the existing events and does not store this one | Decide in your app whether to drop the event or to try again later. The SDK does not store it for you | spec 4 |
| `StorageError(message)` | Storage failed. `message` is the simple class name of the exception. No event content | Treat the event as not stored. Do not show it to the user automatically | spec 3; [ADR-0017](adr/0017-sdk-crash-safety.md) decision 1 |

Notes:

- `track()` does not throw for these cases. An exception thrown by your own `track { ... }` block is not caught (ADR-0017 decision 6). An `Error`, for example `NoClassDefFoundError`, is not caught either (ADR-0017 decision 3).
- Caller cancellation is not a `TrackResult`. See the next section.
- `Success` does not say that the server has the event. To see the device state, use `diagnostics()` (spec 6 "Demo and diagnostics"). Server receipt shows in the Dashboard, not in the SDK (architecture "Components and responsibilities").
- The SDK shows no toast and no dialog (spec 3).

## 5. Cancellation

`track()` propagates `CancellationException`. It does not turn it into a `TrackResult` (spec 3 "Lifetime and cancellation"; architecture "SDK lifecycle and cancellation").

The event may already be stored when the exception reaches you. The SDK keeps a stored event and sends it later (spec 3).

**Do not collect the same action again after a `CancellationException`.** A second `track()` call creates a second event with a new `eventId`. The server then has two events for one action (spec 3; architecture "SDK lifecycle and cancellation").

```kotlin
// sample: cancellation
fun onAddToCart(scope: CoroutineScope, sdk: SignalDock) {
    scope.launch {
        // If this coroutine is cancelled, track() throws CancellationException. The event may
        // already be stored. Do not catch it and do not call track() again for this action.
        sdk.track("add_to_cart") { property("product_id", "P-001") }
    }
}
// end sample
```

What to do and not do:

- Let the exception go up. Do not wrap `track()` in `runCatching` or in a `catch (e: Exception)` that starts the call again.
- Do not call `track()` again for the same action in a `finally` block, a retry loop, or a new screen.
- The SDK writes a developer WARN when a cancellation leaves storage uncertain: `track cancelled during storage; the event may already be stored`. It writes nothing when the cancellation is known to come before storage. Logs are not guaranteed when the process is killed (spec 3; [ADR-0017](adr/0017-sdk-crash-safety.md) decision 2).
- A closed Room database cancels its own scope. The SDK checks that your coroutine is active. If it is active, you get `StorageError`, not an exception (ADR-0017 decision 2).
- Cancelling `initialize()` stops only your wait (section 2).

## 6. Offline, retry, and queue limits

### Collection and delivery

1. `track()` returns `Success` after the Room commit. It needs no server connection (architecture "Commit boundaries").
2. The SDK sends stored events in batches. The foreground trigger starts a send at 20 queued events, or 10 seconds after the first event stored since the last send started (spec 4; [ADR-0006](adr/0006-sdk-delivery-decisions.md) decision 11).
3. WorkManager is the second path: one unique one-time Work that needs a network, and a unique periodic Work every 15 minutes for recovery. One Worker run stops starting batches after 10 batches or 30 seconds, checked between batches (spec 8.A Q57, Q72; ADR-0006 decision 11).
4. The SDK does not change the app's WorkManager configuration. If WorkManager is not ready, the SDK writes a WARN and foreground delivery still works (spec 8.A; ADR-0006 decision 11; ADR-0017 decision 5).
5. The server stores the events and returns an ACK after the database commit. The SDK checks the whole ACK, then removes the confirmed events from the queue (architecture "Collection, delivery, and ACK").

WorkManager does not promise an exact run time (architecture "Agreed defaults, pending measurement").

### Retry

- The SDK keeps the same `eventId` on every resend. A lost ACK gives a `duplicate` answer on the resend. It does not give a second row (architecture "Lost ACKs and event deduplication").
- Temporary failures (network, timeout, 408, 429, 5xx except 501 and 505): the SDK keeps the queue and waits. The wait starts from a base of 10 seconds, doubles, and stops at 5 minutes. The SDK picks a random time between 0 and the base (Full Jitter), so a retry can come almost at once. A longer valid `Retry-After` wins (spec 8.A Q73, Q74; ADR-0006 decisions 5 and 9).
- The next attempt time is saved. A restart or a new event does not skip the wait. The foreground sender and the Worker use the same time (spec 8.A Q74; architecture "Retry and transport boundaries").
- Authentication, configuration, or contract errors (for example 400, 401, 403, 404, a redirect): the SDK pauses normal sending, keeps the queue, and tries one batch every 30 minutes. It sends nothing if the queue is empty. A valid ACK ends the pause (spec 8.B Q58; ADR-0006 decisions 5 and 8). The SDK does not follow a redirect (architecture "Retry and transport boundaries").
- 413 for a batch of more than one event: the SDK splits the batch and keeps the IDs. 413 for one event: the SDK pauses normal sending and tries that event every 30 minutes (spec 8.B Q60; ADR-0006 decision 6).
- A bad ACK (unreadable, missing results, or contradictory) is not applied. The whole batch stays and the SDK waits (spec 8.B Q59; architecture "Validate the entire ACK before changing the queue").
- A known permanent rejection of one event removes it from the queue. The SDK saves a diagnostic without event content (spec 4; architecture "Validate the entire ACK before changing the queue").
- The SDK never discards an event because of a failure count (spec 4). There is no queue TTL (spec 4).
- The failure count goes to zero only after a valid ACK is applied (spec 8.A Q75).

### Limits

| Limit | Value | Source |
| --- | --- | --- |
| One event | 16 KiB of transport JSON, with the common fields | spec 4 |
| One batch | 256 KiB and 50 events | spec 4 |
| Queue | 10,000 events or 16 MiB of transport JSON in total | spec 4 |
| At the queue limit | The SDK keeps the existing events and returns `QueueFull` for new calls | spec 4 |
| Queue TTL | None | spec 4 |
| Permanent rejection diagnostics | The most recent 100 | spec 4 |

The queue budget does not cap the size of the Room file (spec 4).

### Data that does not survive

The SDK stores the installation ID, the queue, the diagnostics, and the retry state in its own area under `noBackupFilesDir`. Android Auto Backup does not restore it (spec 4; [ADR-0001](adr/0001-installation-scoped-identity.md)).

- Data stays through restarts and normal updates of the same installation (spec 4).
- Data does not carry over after clearing app data, a reinstall, or a supported device restore. **Events that were not sent can be lost** (spec 4; architecture "Installation lifecycle and durability").
- The SDK does not change the app's backup settings for other data (spec 4).

### Delivery guarantee

The SDK sends an event again until the server confirms it. It does not claim exactly-once delivery. The server stops duplicate storage while the row exists. If someone deletes the row or resets the database, an old event can be stored again (spec 5; architecture "Lost ACKs and event deduplication").

## 7. Diagnostics

`diagnostics()` is a read-only suspend call. It returns a snapshot of the device queue. It does not describe server state (spec 6 "Demo and diagnostics"; architecture "Components and responsibilities").

```kotlin
// sample: diagnostics
suspend fun queueSummary(sdk: SignalDock): String {
    val d = sdk.diagnostics()
    if (!d.storageAvailable) return "queue could not be read" // the other fields mean nothing
    return "queued=${d.queuedEvents} bytes=${d.queuedBytes} " +
        "lastAck=${d.lastAck?.accepted} lastError=${d.lastDeliveryError?.kind} " +
        "failures=${d.retryFailureCount} nextAttempt=${d.nextAttemptEpochMillis}"
}
// end sample
```

| Field | Meaning | Source |
| --- | --- | --- |
| `sdkVersion` | The SDK release version (`0.1.0`). It is not the contract version | spec 3; spec 8 Q65 |
| `queuedEvents`, `queuedBytes` | Events that wait in the queue, and their transport JSON size | spec 6 |
| `oldestPendingEpochMillis` | Collection time of the oldest queued event. `null` if the queue is empty | spec 6; `Diagnostics` KDoc |
| `recentRejections` | The last permanent rejections: event ID, code, time. No payload and no name | spec 4; ADR-0003 decision 7 |
| `lastAck` | Time and counts (`accepted`, `duplicate`, `rejected`) of the last valid ACK. A recent ACK does not mean that the whole queue was sent | spec 6 |
| `lastDeliveryError` | The last final delivery error: a kind, an optional HTTP status, and a time. It stays as history after recovery | spec 6; ADR-0006 decision 4 |
| `retryFailureCount`, `nextAttemptEpochMillis` | Temporary failures since the last valid ACK, and the earliest next send time | spec 6; spec 8.A Q75 |
| `contractDeprecation` | `null`, or the server's deprecation warning for this process: `deprecatedAtEpochMillis` and `sunsetAtEpochMillis` (each can be `null`). The SDK reads the `Deprecation` and `Sunset` response headers and writes one WARN per process. Delivery does not change. It is in memory only. It is not in `copy()`, `equals()`, or `componentN()` | spec 8 Q159; ADR-0030 |
| `storageAvailable` | `false` when the queue could not be read. The other fields are then empty and mean nothing. The SDK also writes a WARN | ADR-0017 decision 4 |

Notes:

- `diagnostics()` does not throw for a storage failure. It returns `storageAvailable = false` (ADR-0017 decision 4).
- The SDK reads the queue, the retry state, and the rejections in one transaction, so they are one snapshot (ADR-0003 decision 7).
- Times are epoch milliseconds (`Long`), because `java.time` needs API 26 and `minSdk` is 24 (ADR-0003 decision 5).
- Call it when a screen opens and on a manual refresh. There is no Flow API (spec 6).
- The SDK does not poll the server. For server records, use the Dashboard (spec 4; spec 6 "Dashboard").

## 8. Privacy

- **Installation-scoped identity.** The SDK makes one UUIDv4 installation ID at the first initialization. It does not link to a logged-in user. A new ID starts after clearing data, a reinstall, or a supported restore (ADR-0001).
- Each event keeps the installation ID from the time of collection. A resend does not replace it (ADR-0001).
- The SDK has no `identify` call (spec 1).
- The SDK logs to Logcat with the tag `SignalDock`. It never logs event content, event names, installation IDs, credentials, or HTTP bodies, also at `DEBUG` (spec 3; architecture "SDK lifecycle and cancellation"; spec 8 Q115).
- `TrackResult.Invalid.field` is never logged (the KDoc and spec 8 Q115).
- The collection key checks access for local evaluation. The key can be taken out of an app. It is write-only. It does not replace user authentication (spec 5; architecture "Components and responsibilities").

## 9. Versions and the contract version

- **SDK release version:** `0.1.0`. `Diagnostics.sdkVersion` returns it. Each event stores the version from the time of collection (spec 3 "Common information").
- **Contract version:** `1`. The SDK sends it in the header `SignalDock-Contract-Version`. The contract version and the SDK release version are separate. An incompatible change raises the contract version. The first release implements only contract 1 (spec 8.B Q65, Q106; architecture "Contracts, generated sources, and JSON").
- An event keeps the contract version from its collection. After an SDK update, the SDK sends old queued events under their original contract, in batches grouped by contract. Old contracts are not supported without end (spec 8.B Q66). After a new contract ships, the server plans to keep the previous one for at least 12 months and until its traffic is low, and to warn with `Deprecation` and `Sunset` headers. The server part is not built yet. The SDK already reads these headers (spec 8 Q159; [ADR-0030](adr/0030-sdk-contract-deprecation-warning.md)).
- A server that does not support the contract version rejects the whole request. The SDK keeps the queue and checks again every 30 minutes. It does not downgrade by itself (spec 8.B Q67).
- The SDK ignores new compatible fields in a response. An unknown status or a missing field makes the whole ACK invalid (spec 8.B Q68).
- Consumer requirements (`compileSdk`, Kotlin, AGP) are in [section 1](#1-install).

## 10. Troubleshooting

### Errors the SDK can report

`initialize()` returns `InitResult.Failure` with one of these (spec 8.B Q112, Q113, Q118; ADR-0017):

| `InitError` | Cause | What to do |
| --- | --- | --- |
| `InvalidConfiguration(message)` | The `collectionKey` is blank, the `endpoint` is not an absolute `https` URL with a host (an `http` URL is accepted only with `allowInsecureEndpoint = true`), or a timeout is not positive and finite. No SDK state was created | Fix the configuration. The message never contains the key |
| `ConfigurationConflict` | A different configuration is active or starting | Use one configuration for the whole process |
| `StorageFailure(message)` | Storage could not be prepared. `message` is the exception class name | Call `initialize()` again later. The queue and the installation ID stay |

`track()` returns `Invalid`, `QueueFull`, or `StorageError` (section 4). `diagnostics()` reports a storage failure as `storageAvailable = false` (section 7).

Delivery errors do not reach `track()`. They show in `Diagnostics.lastDeliveryError.kind` ([ADR-0006](adr/0006-sdk-delivery-decisions.md) decisions 4 and 5; architecture "Retry and transport boundaries"):

| `DeliveryErrorKind` | When | SDK action | Check |
| --- | --- | --- | --- |
| `NETWORK` | Network error | Keep the queue, back off | Device connection, `endpoint` host and port |
| `TIMEOUT` | Call timeout, 408 | Keep the queue, back off | Server speed, the timeouts in `SignalDockConfig` |
| `RATE_LIMITED` | 429 | Keep the queue, back off. Honor `Retry-After` | Server load |
| `SERVER_ERROR` | 5xx except 501 and 505 | Keep the queue, back off. Honor `Retry-After` | Server health |
| `AUTH_OR_CONFIGURATION` | 401, 403, 404, 415, other 4xx, 3xx, 501, 505 | Pause normal sending. Try one batch every 30 minutes | `collectionKey`, `endpoint` path, redirects |
| `CONTRACT` | 400 | Pause normal sending. Try one batch every 30 minutes | SDK and server contract version |
| `PAYLOAD_TOO_LARGE` | 413 for a single event | Pause normal sending. Try that event every 30 minutes | Size limit of the server |
| `INVALID_ACK` | A 200 answer with an invalid ACK, or a 1xx or other 2xx status | Apply nothing, back off | Server version and proxies between the app and the server |

A 413 for a batch of more than one event is not an error kind. The SDK splits the batch (ADR-0006 decision 6).
A valid `Retry-After` is honored in every row (ADR-0006 decision 5).
A pause ends after a valid ACK (ADR-0006 decision 8).

### Log tags and messages

The tag is `SignalDock`. The default level is `WARN`, so the default shows only WARN and ERROR (spec 8 Q115; `LogLevel`). The messages are fixed text. They hold no event content (ADR-0017 decision 2; `Logger` in the source).

| Level | Message | Meaning |
| --- | --- | --- |
| WARN | `track cancelled during storage; the event may already be stored` | See [section 5](#5-cancellation). Do not collect again |
| WARN | `track storage failed: <ExceptionClass>` | `track()` returned `StorageError` |
| WARN | `diagnostics storage failed: <ExceptionClass>` | `diagnostics()` returned `storageAvailable = false` |
| WARN | `WorkManager request failed: <ExceptionClass>; foreground delivery still works` | WorkManager is not ready or failed. Foreground delivery still works |
| WARN | `delivery run failed: <ExceptionClass>` | A send run failed. The queue is kept |
| WARN | `delivery paused by a configuration, authentication or contract error; rechecking every 30 minutes` | See `AUTH_OR_CONFIGURATION` and `CONTRACT`. The SDK writes this once when the pause starts |
| WARN | `delivery resumed after a successful ACK` | The pause ended |
| WARN | `installation id file is invalid; generating a new id` | The stored ID was not a UUIDv4. The SDK made a new one (ADR-0003 decision 6) |
| DEBUG | `initialized`, `delivery attempt started`, `delivery attempt finished in <n> ms`, `delivery failed: <kind>` | Normal progress. Set `LogLevel.DEBUG` to see them |

Use `LogLevel.DEBUG` only while you debug. `LogLevel.NONE` writes nothing.

### Common questions

- **The Dashboard has no event, but `track()` returned `Success`.** `Success` means "stored on the device". Read `diagnostics()`: `queuedEvents`, `lastDeliveryError`, and `nextAttemptEpochMillis` show why the event waits (spec 6).
- **`lastAck` is old but the queue is not empty.** A recent ACK does not mean that the whole queue was sent (spec 6).
- **Events are missing after a reinstall.** Unsent events do not survive clearing data or a reinstall ([section 6](#data-that-does-not-survive)).
- **Two events for one action.** Check for a second `track()` call after a cancellation ([section 5](#5-cancellation)).
- **A WorkManager WARN appears.** The app's WorkManager is not ready. Foreground delivery still works (ADR-0006 decision 11).
- **Build error "incompatible version of Kotlin" or an AGP error.** See the requirements in [section 1](#1-install).
- **Two processes.** The SDK is for the default process. If two processes run the SDK, both can send the same events. The server then answers `duplicate`. It is safe but wasteful (ADR-0006 decision 10).

## 11. What the SDK does not do

These are outside the first release (spec 1 "Goal and scope"):

- No iOS or Flutter SDK.
- No React Native, Flutter, or Unity bridge, and no WebView JavaScript bridge between native and web events (Q150).
- No automatic events. Your app calls `track()`.
- No `identify` or user identity.
- No campaign or push, no funnel analysis.
- No multiple projects or accounts.
- No public internet operation and no Maven Central publication.

The SDK also does not:

- Say that the server has an event. `Success` is device storage only (spec 3).
- Let your app set `eventId` or `occurredAt` (spec 3).
- Delete queue items for your app, show event content, or give a Flow of diagnostics (spec 6).
- Poll the server database (spec 4).
- Trim, normalize, or fix your input (spec 3; spec 8 Q64, Q84).
- Use `ViewModel` or Compose (spec 2).
- Claim exactly-once delivery (spec 5).
- Give user authentication. The collection key is not a secret (spec 5).
- Make any performance promise. The first release guarantees no throughput (spec 7).

## 12. Code samples

The samples in this guide are copied from [`IntegrationGuideSamplesTest.kt`](../sdk/sdk-android/src/test/kotlin/local/signaldock/IntegrationGuideSamplesTest.kt). The text between its `sample:` and `end sample` markers is the text in this guide. The file compiles with the SDK, and `cd sdk && ./gradlew test` runs the track and diagnostics samples against a real Room database.

A complete app that uses the SDK from the Maven ZIP is the [consumer app](../compatibility/consumer-android/): [`ConsumerApplication.kt`](../compatibility/consumer-android/app/src/main/kotlin/local/signaldock/consumer/ConsumerApplication.kt) and [`MainActivity.kt`](../compatibility/consumer-android/app/src/main/kotlin/local/signaldock/consumer/MainActivity.kt) (ADR-0009 decision 3).
