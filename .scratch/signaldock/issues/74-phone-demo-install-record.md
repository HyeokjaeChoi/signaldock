# 74. Demo app installed on a real phone, with an on-screen E2E result

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #188, 2026-10-20 12:28:32 UTC)

## Scope and completion criteria

Install the demo app on a physical phone and keep it there. Show the E2E result (event sent, server stored, server confirmed) inside the app. No code change: the Diagnostics tab already shows it.

## Install record

- Device: Samsung SM-F766N (Galaxy Z Flip7), Android 16, adb serial `R3KL100KETB`.
- Package: `local.signaldock.demo`, versionName `0.1.0`, versionCode `1`, SDK version `0.2.0`. Debug build of commit `55c3339`.
- Installed: 2026-10-18 23:47 (device local time, KST).
- Build: `sdk/gradlew -p sdk -Psignaldock.collectionKey=<.env key> -Psignaldock.endpoint=http://127.0.0.1:8080 :demo:app:assembleDebug`.
- Install: `adb -s R3KL100KETB install -r sdk/demo/app/build/outputs/apk/debug/app-debug.apk` printed `Success`. The app is not uninstalled.
- Server: `COMPOSE_PROJECT_NAME=signaldock-phone docker compose up -d --build db server` (new volume; the default `signaldock` volume holds an older database password). `/ready` returned 200.
- Link: `adb -s R3KL100KETB reverse tcp:8080 tcp:8080`. It lasts while USB is connected; run it again after a reconnect.

## How to see the result on the phone

1. Open the app "Signaldock Demo". On Shop, tap a product, then "Add to cart". The screen shows "Saved on device: <event> (<id>)" (stored on the device only).
2. Open the Diagnostics tab and tap Refresh after a few seconds. "Pending events" is 0 and "Last ACK from server" shows the time and "Accepted / duplicate / rejected" (for example 1 / 0 / 0). That is the server confirmation.

## Result

Run 2026-10-18: one product tap gave "Saved on device: product_viewed". Diagnostics then showed Pending events 0, Last ACK 23:48:02, 1 / 0 / 0, no delivery error. `select count(*) from events` in the server database returned 2 rows after two taps.
