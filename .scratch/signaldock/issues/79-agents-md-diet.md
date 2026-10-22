# 79. Restructure AGENTS.md into a short index with a rule-to-check table

Status: claimed
Type: task
Blocked by: None
Execution: In an open PR (2026-10-22 UTC)

## Scope and completion criteria

`AGENTS.md` mixed rules that code already enforces with judgment rules and pointers. Restructure it by the user-scope `agents-md-diet` skill (method from the VaultMe AGENTS.md change). Principles: Claude Code memory and best-practices docs (keep only lines whose removal causes mistakes; enforce must-hold rules with checks, not prose); OpenAI Codex AGENTS.md docs (write pointers as "Before you X, read Y").

1. New order: purpose, communication (unchanged), commands, enforced rules (rule-to-check table), judgment rules, where things are.
2. Enforced rules table: each row names a check that exists and was read: `scripts/check-repo-rules.sh` (8 rules), `scripts/verify-contract.sh`, `.github/workflows/pr-description.yml`, `.github/workflows/repo-rules.yml`.
3. No rule changes meaning. "Research models" and "Implementation models" merge into judgment rule 1. "Roles" and "Review workflow" become judgment rules 2 to 7 and the pre-PR command. The issue tracker, triage, domain, design index, and docs-upkeep lines become "Where things are" pointers.
4. References updated: `CONTRIBUTING.md`, `.claude/skills/update-internal-docs/SKILL.md`. Historical records (postmortems, research notes, issue files) stay.
5. No new check: every rule that code can decide is already checked. No new ADR.

Size: 71 lines, 4,251 bytes before; 54 lines, 4,403 bytes after (the table adds bytes and makes the enforced rules visible).

Verification:
- `scripts/check-repo-rules.sh`; every path named in `AGENTS.md` exists (`test -e`); `coderabbit review --base main --deep --agent`.
