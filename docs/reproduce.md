# Reproduction guide

This guide continues the README section "Reproduce from a clean clone". Do steps 1 to 3 there first: toolchain, Compose stack with `.env`, and `scripts/verify-consumer.sh`.
The Android steps need an Android SDK and an emulator. The iOS steps are in [iOS SDK build and tests](#ios-sdk-build-and-tests) and the two sections after it; they need macOS with Xcode.
The iOS sections were not part of that run; their record is [issue 18](../.scratch/ios-sdk/issues/18-ios-docs.md) (clean `git worktree`, 2026-10-29): ZIP, `verify-consumer-ios.sh`, Simulator launch against a separate stack, `simulator-durability.sh offline restart`, and `verify-ios.sh lint` and `manifest` ran. `tsan`, `asan`, `analyze`, and the other durability cases did not run there.
Marks: **ran** means the command ran in a fresh clone on 2026-10-13 (record: issue 25). **not verified here** means it did not run, with the reason.

Set `ANDROID_HOME` first (`export ANDROID_HOME="$HOME/Library/Android/sdk"`). Start one emulator and keep it running. Use an even port:

```sh
"$ANDROID_HOME/emulator/emulator" -avd signaldock-api37 -no-window -no-audio -no-boot-anim -no-snapshot-save -port 5584 &
adb -s emulator-5584 wait-for-device
```

Stop it at the end with `adb -s emulator-5584 emu kill`. AVD names: `signaldock-api24`, `signaldock-api37` ([TOOLCHAIN.md](../TOOLCHAIN.md)). The scripts refuse a serial that does not start with `emulator-`, unless you set `SIGNALDOCK_PHYSICAL_DEVICE=1` to run on your own phone (see [Physical phone](#physical-phone-opt-in)).

## Demo app (ran)

The demo app is a debug build with the SDK as a project dependency. It follows product view, cart, and simulated purchase.

```sh
set -a; . ./.env; set +a
sdk/gradlew -p sdk -Psignaldock.collectionKey="$SIGNALDOCK_COLLECTION_KEY" :demo:app:assembleDebug
adb -s emulator-5584 install -r sdk/demo/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5584 shell am start -n local.signaldock.demo/local.signaldock.demo.app.MainActivity
```

Tap a product, then "Add to cart". The screen shows "Saved on device: add_to_cart (<8 characters of eventId>)". That means the event is in the local queue, not that the server has it. Open `http://127.0.0.1:5173`: after a few seconds the Dashboard lists `product_viewed` and `add_to_cart` with the same ID prefixes. The default endpoint is `http://10.0.2.2:8080`; change it with `-Psignaldock.endpoint=...`.

Evidence: [video](../.scratch/signaldock/evidence/25-demo-track-to-dashboard.mp4) (phone left, Dashboard right), [demo screen](../.scratch/signaldock/evidence/25-demo-saved-on-device.png), [Dashboard](../.scratch/signaldock/evidence/25-dashboard-received.png).

## Delivery failure E2E (case 1 ran; cases 2 to 5 not run for this guide, except as in the case 5 line)

```sh
scripts/e2e-delivery.sh 1         # normal E2E: consumer app and demo app. Or: scripts/e2e-delivery.sh (all five cases)
```

It needs `.env`, the running stack, and a booted emulator. It builds and installs both apps, starts a fault proxy on `127.0.0.1:8081`, and prints a PASS/FAIL summary. It does not delete data. See [ADR-0010](adr/0010-delivery-failure-e2e-harness.md). Cases 2 to 4 (ACK loss, partial rejection, content conflict) have their own results in issue 21. Case 5 (client report: a counted `server_rejected` drop reaches `GET /v1/client-report-counts`, [ADR-0036](adr/0036-sdk-client-reports.md) Amendment) ran in issue 65.

## Device durability (one case ran)

```sh
scripts/device-durability.sh emulator-5584 offline     # 1,000 events offline, process kill, restart, delivery
scripts/device-durability.sh emulator-5584             # all cases (offline kill restart update clear reinstall restore airplane fulldisk workmanager optout)
```

The cases `airplane`, `fulldisk` and `workmanager` switch airplane mode, fill the data partition and lock the WorkManager database ([ADR-0029](adr/0029-device-fault-tests-and-power-loss.md)). `airplane` needs `python3` for the host ACK server. Use an emulator that you can wipe.

It runs the SDK `androidTest` debug build. It needs no server. See [ADR-0011](adr/0011-sdk-device-test-harness.md). Only `offline` ran for this guide; the other cases have results in issue 20.

## iOS SDK build and tests

The iOS scripts need macOS with full Xcode (Swift 6 and `swift format`) and an iOS Simulator runtime of iOS 15 or later with an iPhone. A Command Line Tools shim of `xcodebuild` is not enough. CI uses Xcode 26.6 on `macos-26` ([ADR-0047](adr/0047-ios-sdk.md) "iOS 15 Simulator fallback"); local runs used Xcode 27.1. Gap: older Xcode versions were not tested. The iOS scripts need no Android SDK, JDK, or Docker, except where a step below says so.

```sh
scripts/verify-ios.sh available       # exit 0 only when full Xcode (Swift 6+, swift format) and an iOS 15+ Simulator runtime are usable
IOS_WERROR=1 scripts/verify-ios.sh    # all gates; IOS_WERROR=1 is the CI setting (warnings are errors)
```

`scripts/verify-ios.sh [all|lint|tsan|asan|analyze|manifest|api|api-update [artifact-dir]|available]` runs the gates of ADR-0047 D13. The default is `all`.

| Mode | What it runs |
| --- | --- |
| `lint` | `swift format lint --strict` on `Package.swift`, `Sources/`, and `Tests/` (not the generated models) |
| `tsan` | Simulator unit tests under Thread Sanitizer |
| `asan` | Simulator unit tests under Address Sanitizer and Undefined Behavior Sanitizer |
| `analyze` | `xcodebuild analyze` (for this Swift-only package it is a build) |
| `manifest` | Builds the package and checks that `PrivacyInfo.xcprivacy` is in the resource bundle |
| `api` | Diffs the public API (`.swiftinterface`) with `sdk-ios/api/`. It runs only with the compiler named in `sdk-ios/api/compiler-version` (the CI compiler); with another compiler it prints a skip line |
| `api-update [dir]` | Copies the interface and the compiler version from the CI artifact `api-actual` into `sdk-ios/api/`. Without a directory it builds with the local compiler and prints a warning: the file must come from the CI compiler |

The tests run on the oldest installed iOS Simulator runtime, because the GitHub runner has no iOS 15 runtime. The script prints a note when that runtime is not iOS 15. The package builds for iOS 15, but no test ran on an iOS 15 runtime (ADR-0047 "iOS 15 Simulator fallback").

`scripts/check-repo-rules.sh` holds the repository-wide iOS rules (no package dependency, no `privacy: .public`, required-reason API use, frozen SQL migrations). `scripts/verify-contract.sh` regenerates the Swift models and checks for drift; it runs the Xcode steps only when `scripts/verify-ios.sh available` succeeds (full Xcode with Swift 6 and `swift format`, and an iOS 15 or later Simulator runtime with an iPhone); otherwise it prints a skip line.

## iOS release ZIP and consumer app

The release ZIP holds the SDK source as a Swift package. The first tag is `ios-v0.1.0`; a tag push needs the owner's approval ([ADR-0046](adr/0046-sdk-platform-field.md) decision 8), so no GitHub Release exists yet. Build the ZIP from `HEAD`:

```sh
scripts/build-ios-release-zip.sh 0.1.0     # dist/signaldock-sdk-ios-0.1.0.zip and .sha256
scripts/verify-consumer-ios.sh             # builds a fresh ZIP, unzips it, builds consumer-ios in Debug and Release
```

The version must equal `SignalDockSDK.version`. The script reads the files of `HEAD` and not the working tree, so commit your change first. `scripts/verify-consumer-ios.sh [path/to/zip]` unzips into `compatibility/consumer-ios/.sdk/` (ignored by Git) and builds the app for the Simulator from that folder only. It checks that the privacy manifest is in the app. It starts no Simulator ([ADR-0047](adr/0047-ios-sdk.md) D12 and "Ticket 14 results").

To send events from the consumer app to a local stack, start a separate Compose project with its own ports and an env file outside the repository. Do not use the project of your `.env` if it holds data you need.

```sh
umask 077
IOS_PROJECT="signaldock-ios-$(date +%s)"
IOS_ENV="$(mktemp)"
cat > "$IOS_ENV" <<EOT
SIGNALDOCK_COLLECTION_KEY=$(openssl rand -hex 24)
SIGNALDOCK_QUERY_KEY=$(openssl rand -hex 24)
SIGNALDOCK_DB_NAME=signaldock
SIGNALDOCK_DB_USER=signaldock
SIGNALDOCK_DB_PASSWORD=$(openssl rand -hex 24)
SIGNALDOCK_DB_PORT=15432
SIGNALDOCK_SERVER_PORT=18080
SIGNALDOCK_DASHBOARD_PORT=15173
EOT
docker compose -p "$IOS_PROJECT" --env-file "$IOS_ENV" up -d --build --wait

xcrun simctl list devices available          # pick an iPhone UDID
UDID=<udid>
xcrun simctl boot "$UDID"
xcrun simctl install "$UDID" compatibility/consumer-ios/build/DerivedData/Build/Products/Debug-iphonesimulator/ConsumerIOS.app
set -a; . "$IOS_ENV"; set +a
SIMCTL_CHILD_SIGNALDOCK_ENDPOINT=http://127.0.0.1:18080 \
SIMCTL_CHILD_SIGNALDOCK_COLLECTION_KEY="$SIGNALDOCK_COLLECTION_KEY" \
SIMCTL_CHILD_SIGNALDOCK_AUTORUN=1 \
  xcrun simctl launch "$UDID" local.signaldock.consumerios
```

`SIGNALDOCK_AUTORUN=1` (Debug build only) sends the shop flow once: `product_viewed`, `add_to_cart`, `checkout_started`, and `purchase_completed`. Without it, use the screens by hand. After about 15 seconds, open `http://127.0.0.1:15173`: the four events show with platform `ios`, and the platform filter works. The diagnostics screen of the app shows `lastAck`. The Simulator uses the network of the Mac, so `127.0.0.1` is the local stack. Keep the stack and the env file for the durability section below. Its last paragraph has the cleanup commands.

## iOS Simulator durability

`scripts/simulator-durability.sh <simulator-udid> [case ...]` runs the SDK across process boundaries on an iOS Simulator ([ADR-0047](adr/0047-ios-sdk.md) D15, ADR-0011, ADR-0029). It is local only; CI does not run it. The cases are `kill_store kill_send restart update reinstall optout offline`. The default is all seven. Backup restore and full disk cannot run on a Simulator; the script prints them as `not run`.

It needs a running stack (use the separate Compose project above), `sqlite3`, `python3`, `curl`, and full Xcode. It refuses a UDID that `xcrun simctl list devices` does not list as an iOS Simulator. It never runs `docker compose`.

| Variable | Meaning |
| --- | --- |
| `SIGNALDOCK_COLLECTION_KEY` | Required. Collection key of the stack |
| `SIGNALDOCK_QUERY_KEY` | Required. Query key of the stack |
| `SIGNALDOCK_ENDPOINT` | Server URL. Default `http://127.0.0.1:${SIGNALDOCK_SERVER_PORT:-8080}` |
| `SIMULATOR_DURABILITY_SKIP_BUILD=1` | Reuse the app that `scripts/verify-consumer-ios.sh` built earlier |
| `IOS_WERROR=1` | Passed to the build |

```sh
set -a; . "$IOS_ENV"; set +a
SIGNALDOCK_ENDPOINT=http://127.0.0.1:18080 scripts/simulator-durability.sh "$UDID" offline restart
SIGNALDOCK_ENDPOINT=http://127.0.0.1:18080 scripts/simulator-durability.sh "$UDID"      # all cases
```

Pass means: every event that `track()` reported as stored reaches the server, the server holds no event that the app did not report (one in-flight event is allowed at a kill), and the queue is as the case expects. A resend after a kill shows as `duplicate` in the ACK; the script prints it and does not assert it. The offline state is the closed port `http://127.0.0.1:9`, so no network setting of the Mac changes. The Debug build of the consumer app holds the phase code (`SIGNALDOCK_PHASE`); the script checks that the Release binary does not. The recorded run is in `.scratch/ios-sdk/issues/15-simulator-durability.md`.

When you finish, clean up (this removes only this project and its volume):

```sh
xcrun simctl shutdown "$UDID"
docker compose -p "$IOS_PROJECT" --env-file "$IOS_ENV" down -v     # removes only this project and its volume
rm -f "$IOS_ENV"
```

## Measurements

SDK on a device (ADR-0014). It needs a separate Compose project with its own ports and an env file outside the repository:

```sh
umask 077                                          # the file holds keys and a password: owner-only
MEASURE_PROJECT="signaldock-measure-$(date +%s)"   # one project, one volume, one password per run
MEASURE_ENV="$(mktemp)"                            # a new private file outside the repository
cat > "$MEASURE_ENV" <<EOT                         # values are random
SIGNALDOCK_COLLECTION_KEY=$(openssl rand -hex 24)
SIGNALDOCK_QUERY_KEY=$(openssl rand -hex 24)
SIGNALDOCK_DB_NAME=signaldock
SIGNALDOCK_DB_USER=signaldock
SIGNALDOCK_DB_PASSWORD=$(openssl rand -hex 24)
SIGNALDOCK_DB_PORT=15434
SIGNALDOCK_SERVER_PORT=18090
EOT
docker compose -p "$MEASURE_PROJECT" --env-file "$MEASURE_ENV" up -d --build db server
MEASURE_PROJECT="$MEASURE_PROJECT" SIGNALDOCK_ENV_FILE="$MEASURE_ENV" \
  scripts/device-measure.sh emulator-5584 normal          # or: normal offline burst (default: all three)
docker compose -p "$MEASURE_PROJECT" --env-file "$MEASURE_ENV" down      # keeps the volume
```

It writes `perf/sdk-results/api<api>-<scenario>/` and `perf/sdk-results/summary.md` (the committed results came from the owner's runs; a new run overwrites that folder in your clone, so use a scratch clone). The `normal` scenario ran for this guide. `offline` and `burst` did not.
By default it uses a debug build of the SDK and the emulator, so the numbers are not release numbers and not an SLA. For the R8 release build (ADR-0018) set `MEASURE_BUILD=release`; it needs an emulator image where `adb root` works (`google_apis`, not Google Play) and writes `perf/sdk-results/api<api>-release-<scenario>/`.

### iOS measurement

Measurement: see ticket 16 (pending). `.scratch/ios-sdk/issues/16-ios-measurement.md` plans the size and host-cost measurement on the Simulator; no script for it is in the repository yet.

### Physical phone (opt-in)

ADR-0042. Set `SIGNALDOCK_PHYSICAL_DEVICE=1` and pass the serial of the phone (`adb devices`). Use a debug build only: `MEASURE_BUILD=release` is refused because it needs `adb root`. The scripts change no device setting. They print a notice that this is a physical-device run.

```sh
SIGNALDOCK_PHYSICAL_DEVICE=1 MEASURE_PROJECT="$MEASURE_PROJECT" SIGNALDOCK_ENV_FILE="$MEASURE_ENV" \
  scripts/device-measure.sh <phone-serial> normal
SIGNALDOCK_PHYSICAL_DEVICE=1 scripts/host-cost-measure.sh <phone-serial>
```

- `device-measure.sh` runs `adb reverse` for the proxy port and uses `http://127.0.0.1:<port>` as the endpoint. It removes the reverse at the end.
- Result folders: `perf/sdk-results/api<api>-phone-<scenario>/` and `perf/sdk-results/host-cost-phone-api<api>/`. The device name in the results is the model plus `(physical)`.
- `scripts/device-durability.sh` stays emulator-only. It changes backup, airplane mode, and disk state.
- The test packages (`local.signaldock.test` and its app, or the two consumer apps) stay installed. Remove them yourself if you want.
- Keep the phone awake, unlocked, and on a charger. Other apps change the numbers.

Server load (ADR-0013, [Q133](../.scratch/signaldock/spec.md)): two pilots and one overload run are committed in `perf/results/`, with `perf/report/` as the viewer. **Not verified here:** the pilot and overload runs did not run in the clean clone. `perf/` always uses the Compose project `signaldock-perf` and a fixed volume, and this machine already holds such a volume from the earlier runs; a new clone writes new random passwords that would not match the old database password in it (expected, not tried), and the volume must not be deleted. Only the command line was checked:

```sh
python3 perf/seed.py --help
python3 perf/overload.py --help           # one run: seed 100,000 rows first, then
# python3 perf/seed.py build --size 100000 --dist recent70
# python3 perf/overload.py --run-id <new-id>      (not verified here)
# python3 perf/run_one.py --seed-size 100000 --dist recent70 --window 30 --clients 5 --pool 4 --run-id <new-id> --pilot     (not verified here)
```

The harness needs `k6` and Python 3. A full pilot or overload run takes several minutes. The 216-run matrix is not part of the deliverable.

## Connect a device to the local server

| Device | Address | Status |
| --- | --- | --- |
| Emulator | `http://10.0.2.2:8080` (host loopback, as the emulator sees it) | ran |
| Physical device, USB (Q134) | `adb -s <device-serial> reverse tcp:8080 tcp:8080` (serial from `adb devices`; required when an emulator is also connected), then build the app with `-Psignaldock.endpoint=http://127.0.0.1:8080`. Remove the tunnel with `adb -s <device-serial> reverse --remove tcp:8080`. Use the server port from `.env` (`SIGNALDOCK_SERVER_PORT`) on both sides | ran on a physical device ([issue 74](../.scratch/signaldock/issues/74-phone-demo-install-record.md): Samsung SM-F766N, Android 16, 2026-10-18); also ran on an emulator (issue 25) |
| Physical device, LAN address | not supported over HTTP | Android cannot allow an address range for cleartext; see below |

Both apps allow cleartext HTTP to `10.0.2.2`, `127.0.0.1`, and `localhost` only (network security config; the demo release build allows none). A LAN address over HTTP is refused: use `adb reverse`. Compose publishes the server port on the host's `127.0.0.1` only, so a LAN address would not reach it either. Opening the Dashboard from another machine is also not supported: it binds to `127.0.0.1`.

## Shutdown and reset

```sh
docker compose stop                 # stop; the DB volume is kept
docker compose down                 # remove containers and network; the DB volume is kept
docker compose down --volumes       # RESET: deletes the DB volume and ALL stored events (not run for this guide)
```

`docker compose up -d` after `stop` or `down` shows the old events again. Only `--volumes` gives an empty database. The measurement stacks above use their own project names, so they have their own volumes.
