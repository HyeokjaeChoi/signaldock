# 11. Dependency and repository gates: Renovate validator, OSV-Scanner, main ruleset

Status: claimed
Type: task
Execution: In progress (PR pending)
Blocked by: 03

## Scope and completion criteria

Spec gates R1-R3. ADR-0049 decision 9, ADR-0031.

1. `renovate-config-validator --strict` with an exact `renovate` version in CI job `check`. Prove it fails on a sample with a bad option.
2. OSV-Scanner pinned by version and SHA-256 in `scripts/setup-toolchain.sh`; scans `dashboard/package-lock.json`, `perf/report/package-lock.json`, and `sdk/`, `server/`, `contract-check/`, `compatibility/consumer-android/` `gradle/verification-metadata.xml`. Fails on any finding not in `osv-scanner.toml`.
3. A `scripts/check-repo-rules.sh` check: each ignore entry in `osv-scanner.toml` has a reason and an `ignoreUntil` at most 30 days after the commit date. Prove it fails on a bad sample.
4. `.github/rulesets/main.json`: pull request required, required checks by exact context name (PR jobs of `repo-rules.yml`, CodeQL `analyze (actions)`, `analyze (java-kotlin)`, `analyze (javascript-typescript)`, `analyze (python)`, `analyze (swift)` (the matrix contexts, exactly), `length`), branches up to date before merge, no force push, no deletion, squash only. The apply command goes in `docs/releases/README.md`. The owner applies it; the PR does not.
5. Nightly parts (R2 on `main`, R3 drift check) go into `nightly.yml` after ticket 05 merges; if 05 has not merged, record them as a follow-up in this file.
6. ADR-0049 amendment: first OSV result (finding count, ignores and reasons), validator output, run IDs.
