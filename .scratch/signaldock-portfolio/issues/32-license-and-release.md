# 32. License and first tagged release

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/32-license-and-release, PR pending

## Scope and completion criteria

The repository is public but has no license and no release, so a reviewer cannot tell how the SDK is distributed or whether they may reuse it. Spec section 1 excludes Maven Central only; a GitHub Release with the existing Maven repository ZIP (issue 06, ADR-0009) is in scope.

Decisions (spec Q148, Q149): Apache-2.0; tag `v0.1.0` with a GitHub Release now.

Original questions:
1. License: which license (for example Apache-2.0, MIT, or "all rights reserved, evaluation only")?
2. Release: tag `v0.1.0` from main and attach the Maven repository ZIP and its checksum to a GitHub Release, or not yet?

Completion criteria after the answers:
- `LICENSE` at the root and the README license sentence match the decision.
- The README install steps name the Release artifacts (ZIP and `.sha256`) and the checksum command (Q149).
- The tag `v0.1.0` on main and a GitHub Release with: the release notes (what is included, verified, and not verified, linked to the README); the Maven repository ZIP built by the existing distribution path (`scripts/verify-consumer.sh`, ADR-0009); and a SHA-256 checksum file made by a named release step (a script added in this ticket, since no current script makes a checksum). The consumer app (`compatibility/`) installs from the released ZIP.
- `scripts/check-repo-rules.sh` and `scripts/verify-contract.sh` pass.

## Comments

- 2026-10-14 (PR #80 review): `scripts/verify-consumer.sh` builds the Maven repository ZIP; no current script makes a checksum, so a separate release step added in this ticket makes the SHA-256 file.
- 2026-10-14 (PR #85): the user chose Apache-2.0 (Q148) and a `v0.1.0` tag with a GitHub Release now (Q149). Status set to `ready-for-agent`; issue 31 is resolved, so nothing blocks this issue.
