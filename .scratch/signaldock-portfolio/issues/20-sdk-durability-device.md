# 20. SDK durability on devices (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Done (merged in PR #46, 2026-10-12)

## Scope and completion criteria

Spec section 7 rows "Offline recovery", "Concurrency", "Collection cancellation", "Capacity boundaries", and "Installation lifetime", with the related Q110-Q125 agreements. Run them as instrumented tests on emulators: the minimum API (24) and the selected latest stable version (API 37, AVD `signaldock-api37`), as section 7 requires. Create an API 24 AVD for this.

- Offline recovery: keep 1,000 events with their stored IDs and content across a real process restart and reconnection.
- Concurrency: no queue loss when foreground delivery and the Worker contend, are interrupted, and resume.
- Collection cancellation: cancellation before storage, during storage, and during result delivery after commit; required WARN logs and retention.
- Capacity: 16 KiB event, 256 KiB batch, 10,000 events, 16 MiB queue, concurrent insertion, space recovery after QueueFull.
- Installation lifetime: data kept on restart and app update; excluded on data clear, reinstall, and supported restore.

- Q110-Q118 (initialization results, retry, shared attempts, process-lifetime scope, log level and destination, invalid configuration): confirm each has a test (JVM or instrumented); add missing ones.

Done when the tests pass on both AVDs and every Q110-Q118 item maps to a passing test, with commands, results, and environment (device, OS, build mode) recorded here.

## Comments

2026-10-12: Split from issue 07.

2026-10-12 (implementation, branch `impl/20-sdk-durability-device`, GitHub #42): instrumented device tests, phase script, JVM gap tests. No product code changed. New decisions: [ADR-0011](../../../docs/adr/0011-sdk-device-test-harness.md).

File map:
- `sdk/sdk-android/src/androidTest/kotlin/local/signaldock/device/`: `CapacityDeviceTest`, `ConcurrencyDeviceTest`, `CancellationDeviceTest`, `InitDeviceTest`, `DurabilityPhases` (process-boundary phases, skipped without `-e phase`), `DeviceSupport` (`AckServer` on MockWebServer, `Rig`, Logcat helper). `src/androidTest/AndroidManifest.xml` (INTERNET, cleartext, allowBackup).
- `scripts/device-durability.sh <serial> [case...]`: cases `offline kill restart update clear reinstall restore`.
- `sdk/sdk-android/src/test/kotlin/local/signaldock/InitContractTest.kt`: JVM tests for Q110, Q115-Q117 gaps.
- `sdk/sdk-android/build.gradle.kts`, `gradle/libs.versions.toml` (androidx.test runner 1.7.0, ext:junit 1.3.0), `.github/workflows/repo-rules.yml` (`assembleDebugAndroidTest` in `sdk-test`, compile only), `TOOLCHAIN.md`, `docs/design-index.md`, `docs/adr/0011-sdk-device-test-harness.md`.

Design sections followed: architecture "Collection, delivery, and ACK", "Lost ACKs and event deduplication", "SDK lifecycle and cancellation", "Retry and transport boundaries", "Installation lifecycle and durability"; spec sections 3, 4, 7, 8.A, Q110-Q118; ADR-0001, ADR-0003, ADR-0005, ADR-0006.

Environment (both runs): host macOS (Darwin 25.6.0, arm64), Gradle 9.7.0, AGP 9.3.3, JDK 21.0.12.1+1, headless emulators (`-no-window -no-audio -no-boot-anim -no-snapshot`). Build mode: debug (SDK debug variant and debug test APK, no R8 on the SDK; the R8 release run is issue 06). Devices: AVD `signaldock-api24` = emulator-5554, Android 7.0, API 24, google_apis arm64-v8a; AVD `signaldock-api37` = emulator-5556, Android 17, API 37.2, `sdk_gphone16k_arm64`. No Compose server was used (MockWebServer on the device), so no Docker action was taken.

Commands and results:
- `ANDROID_SERIAL=emulator-5554 sdk/gradlew -p sdk :sdk-android:connectedDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL, 22 tests, 0 failed, 6 skipped (the `DurabilityPhases` phases, by design), 44.4 s.
- `ANDROID_SERIAL=emulator-5556 sdk/gradlew -p sdk :sdk-android:connectedDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL, 22 tests, 0 failed, 6 skipped, 48 s run.
- `scripts/device-durability.sh emulator-5554` (all cases) and `scripts/device-durability.sh emulator-5556` (all cases): both print `all requested cases passed`.

Results per case (API 24 / API 37.2):
- Offline recovery: 1,000 events stored while the endpoint refuses connections; `am force-stop`; new process (pid 9743 -> 9797 / 8439 -> 8511); the persisted rows read without the SDK match the snapshot (IDs and SHA-256 of each stored payload); the real Worker delivered all 1,000 once the persisted retry time passed, in 18.4 s / 18.2 s over 20 requests; the server received 1,000 IDs, 0 duplicates, byte-identical payloads, one installation ID.
- Kill during collection (SIGKILL by `am force-stop` during a `track()` loop that fsyncs every reported success): 2792 acked / 2792 stored (API 24), 2951 / 2951 (API 37.2); `PRAGMA integrity_check` ok; no duplicate rows; the SDK reopens the database.
- Concurrency: the real Worker (two enqueued while a foreground request is in flight) and the foreground trigger delivered 300 events with at most one request in flight and 0 resends; a foreground drain cancelled during HTTP left the queue unchanged, failure count 0, and the resend came back as 50 `duplicate`; a lost ACK (server commits, closes socket) kept the batch and resent byte-identical bodies; a Worker cancelled by WorkManager left 100/100 events and the next Worker finished them.
- Cancellation: before storage (no WARN, nothing stored), during storage before commit (one WARN, nothing stored), after commit (one WARN, event kept and later delivered with its stored ID); WARN lines contain no name, key, value or credential; 300 calls cancelled at random times: no duplicate rows, every reported success stored (API 24: 28 reported, 39 stored, 272 cancelled; API 37.2: 14, 16, 286).
- Capacity: 16 KiB event fits, +1 byte is `EVENT_TOO_LARGE`; every batch body <= 256 KiB and <= 50 events (16 big events per request), exact-limit fit on real rows; 10,000 events then `QueueFull`, one ACKed batch frees exactly 50 places, full drain, then `track` succeeds; 1,024 events of 16 KiB = exactly 16 MiB then `QueueFull`, then recovery after a batch; 2,000 concurrent tracks over the last 1,000 places: 1,000 `Success`, 1,000 `QueueFull`, 10,000 unique rows. Runtime of the 10,000 test: 27 s / 27 s.
- Installation lifetime (installation ID and 5 queued events): restart kept; `adb install -r -t` update kept; `pm clear` new ID, empty queue, no SDK directory before initialization; uninstall + install new ID, empty queue; Auto Backup restore (local transport, `bmgr backupnow`, uninstall, install, `bmgr restore`) brought back the host marker file but not the SDK data: new ID, empty queue. Transports: `android/com.android.internal.backup.LocalTransport` (API 24), `com.android.localtransport/.LocalTransport` (API 37.2).
- Q111 main thread: 200 `track` calls and initialization on the main thread, max main-thread gap 6 ms on both (ticks 36 / 28).
- Other required runs: `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon` passed (94 tests, 0 failures; 4 are new); `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED; `scripts/verify-consumer.sh`: exit 0 (R8 9.3.28, release APK built).

Q110-Q118 test map (JVM = Robolectric `sdk/sdk-android/src/test/kotlin/local/signaldock/`, device = `.../src/androidTest/kotlin/local/signaldock/device/`):
- Q110 (explicit results; offline init; invalid reinit keeps instance; cancellation and fatal errors not swallowed): `LifecycleTest` "storage failure is shared, not retried automatically, and retried by a new call keeping the queue", "unexpected exception during storage creation returns a failure result and allows retry", "a different config is a conflict during the attempt and after success", "public initialize then track then diagnostics" (no server on localhost:8080), "cancelling a waiter does not cancel the shared attempt"; `InitContractTest` "Q110 a fatal Error is not turned into a failure result and a new call can retry"; device `InitDeviceTest.Q110_initialization_succeeds_offline_without_a_reachable_server`.
- Q111 (track only on the returned instance; storage prepared off the main thread): "public initialize then track then diagnostics"; device `InitDeviceTest.Q111_initialization_and_track_do_not_block_the_main_thread`.
- Q112 (explicit retry after storage failure keeps queue and installation ID): `LifecycleTest` "storage failure is shared, not retried automatically, and retried by a new call keeping the queue", "unexpected exception during storage creation returns a failure result and allows retry".
- Q113 (concurrent calls share one attempt; different configuration conflicts): `LifecycleTest` "concurrent initialize with the same config shares one attempt and one instance", "a different config is a conflict during the attempt and after success".
- Q114 (shared attempt continues when waiters cancel): `LifecycleTest` "cancelling a waiter does not cancel the shared attempt".
- Q115 (default WARN/ERROR; DEBUG adds state; no content at any level): `InitContractTest` "Q115 the default level is WARN and only WARN and ERROR reach Logcat", "Q115 DEBUG adds initialization and delivery state but never content"; `LifecycleTest` "config toString hides the collection key", "cancellation during storage propagates and warns without content"; device `InitDeviceTest.Q115_default_level_logs_only_WARN_and_ERROR_and_DEBUG_adds_state_without_content`, `CancellationDeviceTest` (WARN content checks).
- Q116 (Logcat only, no public sink): `InitContractTest` "Q116 and Q117 Logcat is the only destination and the level is fixed at initialization".
- Q117 (level fixed at initialization, no setter): same test (a different level is `ConfigurationConflict`).
- Q118 (invalid configuration fails without state; no edit API): `LifecycleTest` "invalid configuration fails without creating state", "a different config is a conflict during the attempt and after success"; `InitContractTest` Q116/Q117 test (no setter).

CodeRabbit (`coderabbit review --base main --deep --agent`, commit 0c0825f): 1 minor finding, that the PR description must name the decisions of ADR-0011 (decisions 1-10 with reasons). No PR exists yet (nothing pushed); the PR description must carry it. No code finding.

Limits: the update case installs the same build over itself (no SDK version or schema change; no migration exists yet); offline is a refused connection to 127.0.0.1:9, not airplane mode; restore ran on the local backup transport only (not Google, device transfer or manufacturer tools; ADR-0001); SIGKILL through `am force-stop` is not power loss; two emulator images, no physical device; both AVDs ran with the test APK as a debug build; the Compose server path through `10.0.2.2:8080` was not exercised in this issue (MockWebServer on the device instead, ADR-0011 decision 2); emulator runs are not in CI (CI only compiles `assembleDebugAndroidTest`, which also built locally). `README.md` and `architecture.md` evidence rows were left unchanged for this PR.


## Comment 2026-10-12: CodeRabbit minor findings on PR #46

Fixed three minor findings.
- `scripts/device-durability.sh` kill case: right before `am force-stop`, the script now checks `adb shell pidof local.signaldock.test` is not empty and that `files/harness/acked.log` (appended and fsynced by `kill_burst_store` after each reported success) grows over a one-second interval. If not, the case fails with the pid and sizes, because collection had already ended. Host `kill -0` on the adb process was dropped as proof.
- `scripts/device-durability.sh` restore case: the set token is `1` (LocalTransport current set), not the first line of `bmgr list sets`. The script fails before `bmgr restore` if `1` is not listed.
- `DeviceSupport.kt` `Rig.close()`: deletes the rig directory under `noBackupFilesDir` after cancelling the scope, the WorkManager work and the server. The SDK has no `close()` API, so Room's connection is released when the process ends; unlinking the open files is safe on Android. Rooms and sidecar files no longer accumulate across connected runs.

Results on emulator `signaldock-api37` (API 37, serial emulator-5554): `connectedDebugAndroidTest` BUILD SUCCESSFUL (28 tests: 22 ran, 6 skipped as before (DurabilityPhases phase tests that the script drives), 0 failed); `device-durability.sh kill restore` passed (kill: pid alive, acked.log 98753 -> 107374 bytes, acked=2914 stored=2914 integrity ok); full `device-durability.sh` passed all seven cases (kill: acked=2820 stored=2821); `testDebugUnitTest` BUILD SUCCESSFUL (UP-TO-DATE, main sources unchanged). ADR-0011 not changed: nothing in it is now wrong.

CodeRabbit on the fix commit 2c1fe37: the totals line above was corrected (28 total, 22 ran, 6 skipped). A Major finding said the script switches off Backup Manager and selects LocalTransport on any serial, which would change a physical device's backup settings. Fixed with a guard: the script refuses serials that do not start with `emulator-` (the script is emulator-only; the Limits section already states no physical device was used). The Minor finding about the PR description is addressed in the PR text, not in files.

2026-10-12: Merged into main in PR #46.
