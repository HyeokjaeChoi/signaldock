# 13. JDK lookup in scripts and workflows takes the first `jdk-*` directory, not the pinned one

Status: ready-for-agent
Type: task

Found by the `claude-review.yml` round of PR #282 (Minor, AGENTS.md rule 7; the local `/code-review` found the same).

`jdks=("$PWD"/.tools/jdk-*)` then `${jdks[0]}` (in `scripts/check-size-budget.sh`, `scripts/device-durability.sh`, `scripts/update-gradle-verification.sh`, `codeql.yml`, `release.yml`, and three jobs of `repo-rules.yml`) takes the first match in collation order. `scripts/setup-toolchain.sh` keeps old JDK directories. After a JDK bump, a local run can pick the old JDK without an error. CI runners have one JDK, so CI is not affected. `verify-contract.sh` and `codegen.sh` already use the exact path.

Done when each lookup uses the one pinned path from a single source (for example `jdk-21.0.12.1+1` in `scripts/setup-toolchain.sh`), and a JDK bump changes one place.
