# 25. Reproduction README and verification video (part of issue 08)

Status: ready-for-agent
Type: task
Blocked by: 23, 24
Execution: Not started

## Scope and completion criteria

A reproduction README that takes a new reader from a clean clone through toolchain setup, the Compose stack, the SDK Maven ZIP and consumer app, the demo app, the E2E and device scripts, and the measurements, with verified commands. A short verification video (for example `adb screenrecord` plus a Dashboard capture) showing demo `track()` to Dashboard receipt.

The README also covers how an emulator (`10.0.2.2`) and a physical device (host LAN address or `adb reverse`) connect to the local server, shutdown, reset (including volume removal), and limitations, and shows a separate Gradle consumer installing the Maven ZIP by its coordinates (`local.signaldock:sdk-android:0.1.0`) with an R8 release build that runs and reaches the server (spec "Reproduction and deliverables").

Done when a clean-clone run of the README commands is recorded and the video file is in the evidence folder.

## Comments

2026-10-12: Split from issue 08.
