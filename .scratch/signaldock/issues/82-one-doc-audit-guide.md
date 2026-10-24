# 82. Replace the two doc skills with one doc-audit guide

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #201, 2026-10-24 UTC)

## Scope and completion criteria

The repo had two skills for the same job, `update-internal-docs` and `signaldock-stale-cleanup`. Their triggers overlapped ("stale docs", "문서 점검"), and both held the same source-of-truth and exclusion rules. A skill also binds the rule to one agent tool, while AGENTS.md is read by every agent. The user decided on 2026-10-24 UTC to keep the repo facts in one plain doc and to drop both skills.

1. New `docs/agents/doc-audit.md` holds the procedure from both skills (audit, re-verify, unsure items, one docs PR, engineering-doc rules) and the repo facts: who decides, what to verify, doc set, exclusions, issue-file rules, verification. It adds three checks found by an audit eval: recount test numbers, check "not verified" claims against later issues, and compare control lists with every accepted ADR.
2. Remove `.claude/skills/update-internal-docs/` and `.claude/skills/signaldock-stale-cleanup/`. The remote-branch cleanup step is not about docs and is dropped.
3. AGENTS.md "Where things are" and `docs/releases/README.md` step 2 point to the new doc.
4. Historical records that name the old skills (CHANGELOG, release notes, `.scratch`) do not change.

Verification:
- `scripts/check-repo-rules.sh`; no tracked file outside history names the removed skills (`git grep`); `coderabbit review --base main --deep --agent`.
