# 33. SDK crash safety and release-build measurement

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/33-sdk-crash-safety, PR pending

## Scope and completion criteria

The SDK evidence today comes from a debug build on emulators (ADR-0014). Two gaps remain: no test shows that the SDK never throws into the host app, and no measurement uses an R8 release build.

1. Crash safety. Add tests that show the public API does not throw into the host app for the recoverable failures the spec names (invalid input, storage errors such as a full or closed database, network errors, calls before or after initialization, calls from many threads). Each returns or reports the result the spec defines (`TrackResult` and the documented error paths). Where the current code can throw, fix it within the spec; if the spec is silent, record the decision in an ADR. Two propagation paths stay as the spec and tests define them: `CancellationException` propagates (spec section 3; cancellation is not converted into a `TrackResult`), and `NoClassDefFoundError` propagates (`InitContractTest`). A catch must not swallow either.
2. Release build measurement. `scripts/device-measure.sh` (ADR-0014) builds and runs the debug `androidTest` APK, so it cannot measure an R8 build as it is. Add a release-capable path (for example, an option that runs the same `MeasurePhases` scenarios against the SDK minified with R8 in a release-type test build, or a measurement entry point in the consumer app's R8 release build) and record it in ADR-0014 or a new ADR. Keep evidence that the measured artifact used R8 (for example, the R8 mapping file or the build type and `minifyEnabled` in the run metadata). Run it once per scenario on one emulator, and commit the results next to the debug results with the build type named. Compare with the debug results in `perf/sdk-results/summary.md`. Do not change the debug results.
3. Physical device. Not in this ticket's automated scope: the agent cannot reach a physical device. If the user connects one, the same script can run there; otherwise the README keeps saying no physical device was measured.

Verification:
- `cd sdk && ./gradlew test` (and the device test task if new device tests are added), `scripts/verify-contract.sh`, `scripts/check-repo-rules.sh`.
- The measurement summary states build type, emulator image, and API level.

## Comments

- 2026-10-14 (PR #80 review): the no-throw tests cover recoverable failures only; `CancellationException` and `NoClassDefFoundError` keep propagating. `scripts/device-measure.sh` runs the debug `androidTest` APK only, so the R8 measurement needs a new release-capable path with evidence that R8 ran.
