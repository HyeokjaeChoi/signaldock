# Reproduction guide

This guide continues the README section "Reproduce from a clean clone". Do steps 1 to 3 there first: toolchain, Compose stack with `.env`, and `scripts/verify-consumer.sh`.
Marks: **ran** means the command ran in a fresh clone on 2026-10-13 (record: issue 25). **not verified here** means it did not run, with the reason.

Set `ANDROID_HOME` first (`export ANDROID_HOME="$HOME/Library/Android/sdk"`). Start one emulator and keep it running. Use an even port:

```sh
"$ANDROID_HOME/emulator/emulator" -avd signaldock-api37 -no-window -no-audio -no-boot-anim -no-snapshot-save -port 5584 &
adb -s emulator-5584 wait-for-device
```

Stop it at the end with `adb -s emulator-5584 emu kill`. AVD names: `signaldock-api24`, `signaldock-api37` ([TOOLCHAIN.md](../TOOLCHAIN.md)). The scripts refuse a serial that does not start with `emulator-`.

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
