# 31. README first screen and role-neutral wording

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/31-readme-first-screen, PR pending

## Scope and completion criteria

A reviewer who opens the repository cold should see what SignalDock is, what it looks like, and where the evidence is, within the first screen. The project is a portfolio for mobile SDK engineer roles in general, not for one job posting (user, 2026-10-14).

1. Role-neutral wording. Replace "for the Datarize Mobile SDK Engineer role" with role-neutral wording in `README.md`, `README.ko.md`, `docs/architecture/onepager-content.md` (and the generated onepager HTML if it carries the same text), and the "Project purpose" line of `AGENTS.md` (for example: "a portfolio for mobile SDK engineer roles"). Do not add a summary in any job posting's format.
2. Current status. The status banner and the document table are stale ("issues 01 to 25", "ADRs 0001 to 0014"). Update them to the current state (issues 01 to 30, ADRs 0001 to 0016), or reword them so they do not go stale with each new issue or ADR.
3. Visual first screen. Near the top of both READMEs, show one current Dashboard screenshot and one benchmark report screenshot (light or dark, from `.scratch/signaldock-portfolio/evidence/` or a fresh capture of the issue 30 UI), and link the demo video (`.scratch/signaldock-portfolio/evidence/25-demo-track-to-dashboard.mp4`). If the images must move out of `.scratch/`, put them under `docs/images/` and keep file sizes small (PNG under about 500 KB each).
4. Visibility wording. The repository is public on GitHub, but the README says the materials are for private technical evaluation. Keep the license sentence until issue 32 decides the license, but remove the claim that the repository is private.
5. Keep `README.md` and `README.ko.md` in sync (`scripts/check-repo-rules.sh` checks headings and links).

Verification:
- `scripts/check-repo-rules.sh` passes.
- Every relative link and image in both READMEs resolves to a tracked file.
- Screenshots of the rendered first screens of both `README.md` and `README.ko.md` (GitHub-like Markdown preview) are checked by eye.

## Comments

- 2026-10-14 (PR #80 review): the visual check covers both README variants, not one.
