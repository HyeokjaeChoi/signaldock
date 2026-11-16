# 18. Ticket 11 review findings left open (Minor and lower)

Status: ready-for-agent
Type: task

Found by the review of PR 295 (ticket 11, ADR-0049 R1 to R3), plus notes of the author. None blocks a gate.

1. (Minor) `scripts/check_ruleset.py static` checks `repo-rules.yml` and `pr-description.yml` for a `paths` filter, but not `codeql.yml`, whose `analyze (*)` contexts are required too. A `paths` filter there would block merges that touch no matching path. Add the check and a test.
2. (Nit) `scripts/check-repo-rules.sh`: for the root `osv-scanner.toml`, the "sits next to a listed lockfile" regex matches any slash-free line of `osv-scan.sh`, so the check passes for the root file whatever the list says. Match a `LOCKFILES` entry with no slash that ends in `package-lock.json` or `verification-metadata.xml`.
3. The `osv-scanner.toml` validator is an inline Python block in `check-repo-rules.sh`. Move it to its own file with unit tests, as `check_ruleset.py`.
4. `scripts/osv-scan.sh` repeats the version `2.7.1` as a string; take it from one place with `setup-toolchain.sh`.
5. `check_ruleset.py drift`: the subset check does not see an extra live `required_status_checks` entry that has the same context and another `integration_id`.
6. `nightly.yml` job `ruleset-drift` is red until the owner applies `.github/rulesets/main.json` (documented in `docs/releases/README.md`). Device and iOS contexts may flake and, with no retry (decision 5), block a merge; watch it after the apply.
7. After the apply, the `osv-scan` expiry on 2026-12-15 blocks every PR merge unless issue 15 is done first.
