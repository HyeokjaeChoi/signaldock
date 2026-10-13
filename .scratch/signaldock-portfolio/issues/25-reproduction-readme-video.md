# 25. Reproduction README and verification video (part of issue 08)

Status: resolved
Type: task
Blocked by: 23, 24
Execution: Done (merged in PR #57, 2026-10-13 UTC). Physical device and full measurement runs are not verified (see the comments).

## Scope and completion criteria

A reproduction README that takes a new reader from a clean clone through toolchain setup, the Compose stack, the SDK Maven ZIP and consumer app, the demo app, the E2E and device scripts, and the measurements, with verified commands. A short verification video (for example `adb screenrecord` plus a Dashboard capture) showing demo `track()` to Dashboard receipt.

The README also covers how an emulator (`10.0.2.2`) and a physical device (`adb reverse` to `127.0.0.1`; a host LAN address over HTTP is not supported, Q134) connect to the local server, shutdown, reset (including volume removal), and limitations, and shows a separate Gradle consumer installing the Maven ZIP by its coordinates (`local.signaldock:sdk-android:0.1.0`) with an R8 release build that runs and reaches the server (spec "Reproduction and deliverables").

Done when a clean-clone run of the README commands is recorded and the video file is in the evidence folder.

## Comments

2026-10-12: Split from issue 08.

2026-10-13: Implemented. `README.md` section "Reproduce from a clean clone" and `docs/reproduce.md` hold the steps. The commands were run in a fresh clone of `https://github.com/HyeokjaeChoi/signaldock.git` (origin/main `da51462`, a temporary folder under `/private/tmp`, removed afterwards). The commands came from this branch's `README.md` and `docs/reproduce.md`; at that point the branch differed from `da51462` only in those docs and the evidence files (no script or code change), so the clone ran the same scripts the guide names. The later Q134 config change was checked separately on the branch build (comment below). Host: macOS, Apple M2 Pro. Emulator `signaldock-api37`, port 5584. Compose project name `signaldock-readme` (set with `COMPOSE_PROJECT_NAME`, so the commands read as in the README; the default `signaldock` project and volume were not touched). No volume was deleted. The new volumes `signaldock-readme_signaldock-db` and `signaldock-readme-measure_signaldock-db` stay on this machine.

Wall times are from a warm machine: the Docker layer cache and `~/.gradle` already held the dependencies, so a first run on a new machine takes longer (downloads).

| Command | Result | Time |
| --- | --- | --- |
| `git clone <origin url>` | ok | a few seconds (not timed) |
| `git config core.hooksPath .githooks` | ok | 0 s |
| `scripts/setup-toolchain.sh` | JDK 21.0.12.1+1, oasdiff 1.33.0, openapi-generator 7.26.0 installed | 12 s |
| `cp .env.example .env` and the `sed` / `openssl rand -hex 24` loop (keys and DB password) | ok | 0 s |
| `docker compose up -d --build` | db healthy, server and dashboard started (images were cached) | 5 s |
| `curl ... http://127.0.0.1:5173/` | 200 | 0 s |
| `scripts/verify-consumer.sh` | ZIP built, Kotlin metadata check ok, consumer R8 release built (R8 9.3.28) from the unzipped folder by coordinate; BUILD SUCCESSFUL | 54 s |
| consumer `gradlew ... -Psignaldock.collectionKey=<key> :app:assembleRelease` | BUILD SUCCESSFUL | 31 s |
| emulator start, `adb install -r` app-release.apk, `am start`, tap "Track consumer_ping", wait 16 s, tap "Show diagnostics" | `lastAck` accepted=1, queuedEvents=0; event in the Dashboard | about 25 s after boot |
| `sdk/gradlew -p sdk -Psignaldock.collectionKey=<key> :demo:app:assembleDebug`, `adb install -r` | BUILD SUCCESSFUL | 31 s |
| demo: tap product, tap "Add to cart", screen recording and Dashboard capture | `product_viewed` and `add_to_cart` stored; IDs match the demo screen | 41 s recording |
| `scripts/e2e-delivery.sh 1` | PASS case 1 (consumer app and demo app, Dashboard detail, list, by-name counts) | 102 s |
| `scripts/device-measure.sh emulator-5584 normal` (separate Compose project `signaldock-readme-measure`, own env file and ports) | verdict pass | 97 s (includes the stack build) |
| `scripts/device-durability.sh emulator-5584 offline` | pass: 1,000 events offline, process kill, recovery, delivered in 18.1 s | 34 s |
| `python3 perf/overload.py --help`, `python3 perf/seed.py --help` | usage printed | 0 s each |
| `docker compose stop`, `up -d`, event count, `docker compose down` | 6 events before and after stop/up (volume kept); containers and network removed | 18 s |
| `adb reverse tcp:8080 tcp:8080`, `adb reverse --list`, `adb reverse --remove tcp:8080` (emulator only) | command works on an emulator | 0 s |

Run in full: the clean-clone path (toolchain, Compose, ZIP, consumer R8 on emulator, demo app). Represented by one short command each: E2E (case 1 of 4), device durability (`offline` of 7 cases), SDK device measurement (`normal` of 3 scenarios, API 37 only).

Not verified, with reasons:
- Physical device (LAN address with `SIGNALDOCK_BIND_HOST`, `adb reverse` on a device): no physical device attached. The README says so. This first version of the comment said a physical device needs HTTPS; Q134 (below) replaced that: a physical device uses `adb reverse` and `http://127.0.0.1:PORT`, a LAN address over HTTP is unsupported, and physical-device operation remains unverified.
- Server pilots and the overload run (`perf/run_one.py`, `perf/overload.py`): not run. The harness uses a fixed Compose project `signaldock-perf` and volume that already exist on this machine from the earlier runs; a new clone would write new random passwords, so the old database would probably reject them, and the volume must not be deleted. Only `--help` ran. The committed results in `perf/results/` are from issue 23.
- E2E cases 2 to 4, durability cases other than `offline`, `device-measure.sh` scenarios `offline` and `burst`, API 24: not re-run; results are in issues 20, 21, and 24.
- `docker compose down --volumes` (reset): not run, by rule. The README warns that it deletes data.
- A first-run download from a machine with empty caches: not measured.

Evidence (folder `.scratch/signaldock-portfolio/evidence/`):
- `25-demo-track-to-dashboard.mp4`: 41 s, 1576x1000, about 360 KB. Left: emulator screen recording (`adb shell screenrecord`) of the demo app. Right: headless Chrome captures of the Dashboard every 1.5 to 2 s. The event ID prefixes on the Dashboard match the demo screen. Frames checked: no key and no secret shown.
- `25-demo-saved-on-device.png`: demo screen "Saved on device: add_to_cart (8bdf0699)".
- `25-dashboard-received.png`: Dashboard with `product_viewed`, `add_to_cart`, and the earlier `consumer_ping`.

Note: the side-by-side video is aligned by start time only, not frame-exact. "Saved on device" does not mean server receipt; the Dashboard frames show receipt.

2026-10-13 (Q134): The earlier HTTPS advice for a physical device could not be followed (the local server has no TLS). User decision Q134 (option a, recorded in spec.md and ADR-0009): a physical device uses `adb reverse tcp:PORT tcp:PORT` and `http://127.0.0.1:PORT`. The cleartext `domain-config` of the demo debug build and of the consumer app now lists `10.0.2.2`, `127.0.0.1`, and `localhost` (`includeSubdomains="false"`). The demo release build stays HTTPS only. No SDK code or default changed. A LAN address over HTTP stays unsupported. README and `docs/reproduce.md` were updated; the "HTTPS required" advice is removed.

Verification on an emulator (`signaldock-api37`, port 5584; physical device NOT used), separate Compose project `signaldock-q134` with fresh random keys, no volume deleted (the `signaldock-q134_signaldock-db` volume stays):
- `scripts/verify-consumer.sh`: ok (R8 9.3.28), 149 s for the whole sequence below including the stack build and emulator boot.
- Consumer release and demo debug built with `-Psignaldock.endpoint=http://127.0.0.1:8080` and the collection key; `adb reverse tcp:8080 tcp:8080`; both apps installed.
- Events table before: empty. Consumer: tap Track, wait 18 s, Show diagnostics: `accepted=1`, `queuedEvents=0`. Demo: product, Add to cart, wait 22 s.
- Events table after (DB count by name): `add_to_cart` 1, `consumer_ping` 1, `product_viewed` 1. So both apps reached the server over `127.0.0.1` through the reverse tunnel.
- `adb reverse` removed, emulator killed, stack stopped with `down`.
- `scripts/check-repo-rules.sh`: ok.
Still not verified: a physical device (none attached) and USB behavior of a real phone.
