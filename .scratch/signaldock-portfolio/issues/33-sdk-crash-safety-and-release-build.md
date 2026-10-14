# 33. SDK crash safety and release-build measurement

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

The SDK evidence today comes from a debug build on emulators (ADR-0014). Two gaps remain: no test shows that the SDK never throws into the host app, and no measurement uses an R8 release build.

1. Crash safety. Add tests that show the public API does not throw into the host app for the failures the spec names (invalid input, storage errors such as a full or closed database, network errors, calls before or after initialization, calls from many threads). Each returns or reports the result the spec defines (`TrackResult` and the documented error paths). Where the current code can throw, fix it within the spec; if the spec is silent, record the decision in an ADR.
2. Release build measurement. Run the existing device measurement (`scripts/device-measure.sh`, ADR-0014) once per scenario on one emulator with an R8 release build of the demo or consumer app, and commit the results next to the debug results with the build type named. Compare with the debug results in `perf/sdk-results/summary.md`. Do not change the debug results.
3. Physical device. Not in this ticket's automated scope: the agent cannot reach a physical device. If the user connects one, the same script can run there; otherwise the README keeps saying no physical device was measured.

Verification:
- `cd sdk && ./gradlew test` (and the device test task if new device tests are added), `scripts/verify-contract.sh`, `scripts/check-repo-rules.sh`.
- The measurement summary states build type, emulator image, and API level.
