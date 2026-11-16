# 16. API 24 gives the SDK queue `synchronous=FULL`: the pin test fails there

Status: resolved
Type: task Execution: Done (PR #302, 2026-11-17 UTC). Nightly 43881303501: `powerloss` reports `synchronous=2` (FULL) on API 24 and `1` (NORMAL) on API 37.0. Not verified: `PowerLossSemanticsDeviceTest` on API 24 (CI `device-test` runs API 34; local disk full).

Found by ticket 06 (ADR-0049 amendment 2026-11-15). On the API 24 emulator, `PowerLossSemanticsDeviceTest` fails: the SDK connection reports `PRAGMA synchronous` = 2 (FULL), not 1. The Android platform default for WAL on that release is FULL. CI `device-test` runs API 34, where the test passes, so no job is red.

Open questions (Minor, no gate is affected):
1. Make the pin API-aware (assert 1 where the platform default is NORMAL, record the value elsewhere), or assert the value the platform gives and say so.
2. Update ADR-0029 item 4: "NORMAL is the Android default for WAL" holds for new releases only; on API 24 every commit syncs, which is safer for power loss.
3. The A6 gate cannot lose data at API 24 for this reason; nothing to fix there.
