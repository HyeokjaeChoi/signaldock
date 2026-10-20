# 75. Stale status cleanup after the real-device run

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #189, 2026-10-18 UTC)

## Scope and completion criteria

Audit with the `update-internal-docs` skill at `main` @ `f08226b` (2026-10-18 UTC). PR #187 merged the real-device run, but some status lines still said "no physical device".

1. Issue 73: `Status: claimed` becomes `resolved`; `Execution:` names PR #187.
2. `architecture.md`, `README.md`, `README.ko.md`, `docs/engineering/threat-model.md`: say that the instrumented tests ran once on a physical device (issue 73). Measurement and `scripts/device-durability.sh` on a physical device stay marked as a gap.
3. Not changed: ADR text (for example the "in review as PR 159" line in ADR-0036), `docs/reproduce.md` (`adb reverse` was not run on the phone), attestation `Gap:` lines (no release since ADR-0039).

Verification:
- `scripts/check-repo-rules.sh`; every relative Markdown link exists.
