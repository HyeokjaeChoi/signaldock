# 11. Docs name the lint tools that pre-commit needs

Status: ready-for-agent
Type: task

Found by the `claude-review.yml` round of PR #282 (Minor, AGENTS.md rule 7).

`.githooks/pre-commit` now needs `.tools/shellcheck` and `.tools/actionlint` (`scripts/setup-toolchain.sh lint`, ADR-0049 amendment 2026-11-15). These docs do not say so:
- `AGENTS.md` Commands: "Pre-commit runs `scripts/check-repo-rules.sh`" (ticket 01 owns `AGENTS.md` in wave 1).
- `CONTRIBUTING.md:13`, `README.md:130`, `README.ko.md:132`: the toolchain list says "JDK 21, oasdiff, OpenAPI generator". Change the README pair together (`scripts/check-repo-rules.sh` keeps them in step).

Done when the four files name shellcheck and actionlint and the pre-commit line names both scripts.
