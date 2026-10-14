# 32. License and first tagged release

Status: needs-triage
Type: task
Blocked by: 31
Execution: Not started

## Scope and completion criteria

The repository is public but has no license and no release, so a reviewer cannot tell how the SDK is distributed or whether they may reuse it. Spec section 1 excludes Maven Central only; a GitHub Release with the existing Maven repository ZIP (issue 06, ADR-0009) is in scope.

Open questions (need a user decision, spec Q entry):
1. License: which license (for example Apache-2.0, MIT, or "all rights reserved, evaluation only")?
2. Release: tag `v0.1.0` from main and attach the Maven repository ZIP and its checksum to a GitHub Release, or not yet?

Completion criteria after the answers:
- `LICENSE` at the root and the README license sentence match the decision.
- If a release is made: the tag, the release notes (what is included, verified, and not verified, linked to the README), and the ZIP plus SHA-256 built by the existing distribution script; the consumer app (`compatibility/`) installs from the released ZIP.
- `scripts/check-repo-rules.sh` and `scripts/verify-contract.sh` pass.
