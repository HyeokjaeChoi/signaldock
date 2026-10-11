# 09. Verification evidence record for issue 01

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Source

PR #9 deep review (`coderabbit review --base main --deep`, 2026-10-11).
Severity: Minor. The merge is not held for this issue (AGENTS.md, Review workflow, rule 5).

## Problem

The "Execution evidence" section of `01-toolchain-contract.md` (lines 38-44) states results without the command that produced them:

- Line 40: Kotlin models compile with kotlinc 2.4.21. No command is named.
- Line 41: `contract-check` round-trip tests: 5/5 pass. No command is named.
- Line 42: TypeScript models compile with tsc 7.0.2 (strict). No command is named.
- Line 43: oasdiff reports "No unrecorded change". No command is named.

The "Environment limitation" section (lines 46-47) states that the Gradle daemon could not run in the 2026-10-09 sandbox. The report therefore does not show a Gradle run. The report also does not say which checks ran in that sandbox.

AGENTS.md: "Never label a step verified without a run."

## Acceptance criteria

- Each check claim in the "Execution evidence" section names the exact command, the date, and the result.
- The direct `kotlinc` and `tsc` runs are recorded as the checks that ran on 2026-10-09. Each entry has the exact command and the tool version.
- If the exact command for a claim is not known from the implementation log or the shell history, mark that claim "not verified". Do not rebuild a command from memory.
- The `contract-check` 5/5 claim and the oasdiff claim are marked "not verified" unless a run record exists for each.
- The record does not say that the full `scripts/verify-contract.sh` ran on 2026-10-09. The record does not say that Gradle ran on 2026-10-09.
- The record does not say that the file-structure claim in line 44 is a build result. If it is kept, label it as a file check.
- Add a separate entry for the full run on 2026-10-11. Cite the pre-push hook run and the CI job `verify-contract`. Link the PR #9 description Test report. Record only the results shown in that report or in the CI log.
- Do not run `scripts/verify-contract.sh` to create evidence for 2026-10-09.
- Keep the "Environment limitation" text as a record of what happened on 2026-10-09.
- Do not change product code, pinned versions, or the contract.

## File to edit

- `.scratch/signaldock-portfolio/issues/01-toolchain-contract.md`: the "Execution evidence" section (lines 38-44) and the "Environment limitation" section (lines 46-47).

## Comments

2026-10-11: Created from the PR #9 deep review (Minor). Status is `ready-for-agent` because the scope and the expected record are fixed.
