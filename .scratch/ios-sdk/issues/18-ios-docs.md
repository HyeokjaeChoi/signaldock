# 18. iOS docs

Status: ready-for-agent
Type: task
Blocked by: 14, 15

## Scope and completion criteria

Spec sections 1, 5, 6.

1. `README.md` and `README.ko.md` in step: iOS install from the ZIP, what is out of scope, platform differences and known limits.
2. `docs/integration-guide.md` iOS section: setup, API, opt-out, privacy manifest note for host-defined property data, and the enum rule: minor versions can add cases to public result enums, so use `default` in `switch` (ADR-0047 amendment, 2026-10-28).
3. `docs/reproduce.md`: iOS build, consumer app, durability script, measurement.
4. `CHANGELOG.md`; `architecture.md` decision rows for ADR-0046 and ADR-0047.
5. Doc audit for the changed areas (`docs/agents/doc-audit.md`).

Verification:
- `bash scripts/check-repo-rules.sh`; README commands run from a clean clone (record in the issue file).
