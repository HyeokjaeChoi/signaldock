# 08. iOS public API: initialize, track, opt-out, diagnostics

Status: ready-for-agent
Type: task
Blocked by: 06, 07

## Scope and completion criteria

Spec D1, D9. ADR-0034, ADR-0035, ADR-0030, ADR-0019, ADR-0047.

1. `async` API one to one with Android 0.3.0: `initialize(config)` with the same results and concurrency rules (shared work, caller-only cancellation), `track(name) { builder }` with the same property types and validation (`fixtures/validation`), `setOptOut`, `clearQueue`, `diagnostics`. Results as `enum`s; public types `Sendable`. Entry points `@available(iOSApplicationExtension, unavailable)`.
2. HTTPS-only endpoint check (ADR-0035). Logging through `os.Logger`, WARN and ERROR by default, identifiers and payloads `.private`.
3. Public API check: `.swiftinterface` from a check-only build in `sdk-ios/api/`, diffed in `scripts/verify-contract.sh` (`AGENTS.md` row).
4. DocC-style documentation on every public declaration (format gate from ticket 05).
5. Storage failure gives the Android crash-safety results (ADR-0017), moved from ticket 07: `StorageError` from the storage layer becomes `InitError.StorageFailure` (no instance), `TrackResult.storageError`, and `storageAvailable = false` in `diagnostics()`; nothing is thrown into the host.
6. Tests: also the failure cases of item 5, and every `TrackResult` and `InitResult` case; opt-out race of ADR-0034 decision 8; validation fixtures shared with Android.

Verification:
- `xcodebuild test` (TSan and ASan/UBSan runs), API check output, `scripts/verify-contract.sh`.
