---
status: accepted
---

# Measurement on a physical device (opt-in)

The owner asked to run the device measurement scripts on a personal Android phone. This ADR records how, and what stays emulator-only. It amends ADR-0014, ADR-0018, and ADR-0020.

## Context

- `scripts/device-measure.sh` and `scripts/host-cost-measure.sh` refused every serial that did not start with `emulator-`. The reason: an emulator can be reset, a personal phone cannot.
- The scripts used `adb emu avd name` (emulator console only) and `10.0.2.2` (the emulator address of the host). Both fail on a phone.
- `host-cost-measure.sh` cleared the log with `adb logcat -c`. On a phone this removes the logs of other apps.

## Decision

1. The opt-in is the environment variable `SIGNALDOCK_PHYSICAL_DEVICE=1`. Without it, the `emulator-*` guard is unchanged and emulator runs behave as before. With it, any serial is allowed and the script prints a one-line notice that this is a physical-device run.
2. The scripts change no device setting. They only install packages, clear the data of their own packages, and start them.
3. Debug build only. `device-measure.sh` refuses `MEASURE_BUILD=release` on a phone, because the release path needs `adb root`. `host-cost-measure.sh` installs its consumer apps (R8 release, debug-signed, ADR-0020) as on an emulator. Neither script needs root.
4. `device-measure.sh` makes the proxy reachable with `adb reverse tcp:<port> tcp:<port>` and uses `http://127.0.0.1:<port>` as endpoint. The reverse is removed in the cleanup (`adb reverse --remove`, errors ignored). `meta.json` records the real path.
5. The device name is `ro.product.model` plus ` (physical)`. `meta.json` gets `"physical_device": true`.
6. Result folders get `phone-`: `perf/sdk-results/api<api>-phone-<scenario>` and `perf/sdk-results/host-cost-phone-api<api>`. `perf/sdk_summarize.py --all` reads every `*/run.json`, so it needs no change except one sentence in the summary header. A phone row shows `(physical)` in the AVD column.
7. `host-cost-measure.sh` on a phone reads logcat with `-T <device time>` from the start of each launch. It does not clear the log. Emulator runs still use `adb logcat -c`. The time has a resolution of one second, so a line from the second before a launch can appear. The script waits at least one second after `force-stop`, so this does not happen in practice.
8. `scripts/device-durability.sh` stays emulator-only. It changes backup, airplane mode, and disk state.

## Consequences

- Phone numbers are not comparable with emulator numbers. They depend on the model, the thermal state, and other apps.
- Side effects that remain on the phone: the test packages stay installed (`local.signaldock.test` with its test runner, or the two consumer apps), and their data stays until the next `pm clear`. A script run can fail midway and leave them. The owner removes them.
- The phone must stay awake, unlocked, and connected. The scripts do not set stay-awake, because that is a device setting.
- A phone with a restrictive USB install policy may ask for a confirmation on screen. The scripts do not handle it.

## Gaps

- Gap: no phone run was made when this ADR was written. The changes were checked with `bash -n` and a read of the code only.
