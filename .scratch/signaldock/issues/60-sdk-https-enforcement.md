# 60. SDK rejects a non-HTTPS endpoint unless the host opts out

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #160, 2026-10-17 03:03:07 UTC)

## Scope and completion criteria

Spec Q163: the SDK rejects an endpoint whose scheme is not `https` at init, with an error that names the option, unless the host sets `allowInsecureEndpoint = true`. Default is false.

1. `initialize` returns `InvalidConfiguration` for `http` (any case) without the flag, and for other schemes and malformed URIs. `http` with the flag is accepted. Unit tests cover https, http, http with flag, and malformed.
2. Public API: `SignalDockConfig.allowInsecureEndpoint`. Update the API dump, both integration guides, `CHANGELOG.md`, the threat model (keep the "no pinning" gap), and the design index.
3. The demo app and the consumer app set the flag when their endpoint starts with `http://`. Device test and measure configs set it.

Verification:
- `sdk/gradlew -p sdk :sdk-android:test`; `:sdk-android:apiCheck`; demo app build; `scripts/verify-consumer.sh`; the pre-push hook (`scripts/verify-contract.sh`); `scripts/check-repo-rules.sh`.

## Comments

- 2026-10-17 UTC: PR #160 merged 03:03:07Z (ADR-0035, spec Q163). Checks in the PR: `:sdk-android:test`, `apiCheck`, `verify-consumer.sh`, `verify-contract.sh` passed; `coderabbit review --base main --deep --agent`: 0 findings.
- Review (PR #160): the binary-level change of `SignalDockConfig` (JVM constructor and `copy` signatures) is listed under Changed in `CHANGELOG.md`.
- Locally the instrumented tests were only compiled (`compileDebugAndroidTestKotlin`). The CI `device-test` job (`connectedDebugAndroidTest` on an emulator) ran them on the merged head of PR #160 and passed: run 38996630244, job 117290516785.
