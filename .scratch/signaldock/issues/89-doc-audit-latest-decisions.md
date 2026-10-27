# 89. Doc audit: align docs with Q159 and phone runs

Status: resolved
Type: task
Blocked by: None
Execution: pending

## Scope and completion criteria

Doc audit after the merges of issues 73, 85, and 86 (`docs/agents/doc-audit.md`). Fixes:

1. `.scratch/signaldock/remaining-decisions.md`: the previous-contract support period was listed as undecided. Q159 (`.scratch/signaldock/spec.md`, 2026-10-16) decided it. The item now names what stays open: the threshold value (ADR-0030 "Gaps") and the deferred server part. The iOS SDK exclusion sentence is unchanged because PR #237 is not merged.
2. `README.md` and `README.ko.md`: the lead said measurement ran "on emulators only", but the same line records one phone run (issue 85, PR #204). Lead corrected in both files.
3. `TOOLCHAIN.md`: Maven coordinate `0.1.0` changed to `0.3.0` (`sdk/sdk-android/build.gradle.kts`).
4. `docs/adr/0011-sdk-device-test-harness.md`: dated amendment for the phone runs (issues 73 and 85). The original "Limits" text is unchanged.

Checked and kept:

- ADR-0009 lines 11-12 `0.1.0`: dated decision, history.
- ADR-0018:35, ADR-0014:30, ADR-0034:35, ADR-0036:46: already amended.
- ADR-0030:62, ADR-0040:49: still true.
- Release notes: history.

Verification:
- `bash scripts/check-repo-rules.sh`.
