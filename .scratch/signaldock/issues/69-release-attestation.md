# 69. Build provenance attestation for the release ZIP

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #180, 2026-10-18 00:45:46 UTC)

## Scope and completion criteria

Spec Q167. The decisions are in ADR-0039.

1. The `publish` job of `.github/workflows/release.yml` attests `dist/*.zip` with `actions/attest-build-provenance` (full commit SHA pin) before `gh release create`. Job permissions: `contents: write`, `id-token: write`, `attestations: write`. The `build` job stays `contents: read`.
2. No repository setting changes. The files inside the ZIP are not attested separately (ADR-0039 item 5).
3. Consumer verification (`gh attestation verify <zip> --repo HyeokjaeChoi/signaldock`) is in `docs/releases/README.md`, `README.md`, and `SECURITY.md`.
4. Docs: ADR-0039, spec Q167, `docs/engineering/threat-model.md` (supply-chain and release rows), `CHANGELOG.md`, `docs/design-index.md`.

Verification:
- YAML parse (`yq`) and `actionlint` 1.7.12 on `release.yml`; `scripts/check-repo-rules.sh`.
- Gap: no tag push, so the attestation is not produced until the next release tag. After that tag, run `gh attestation verify` on the released ZIP.

## Comments

- 2026-10-18 UTC: PR #180 merged 00:45:46Z. Verification in the PR: `actionlint` 1.7.12 on `release.yml` gave no findings; `yq` parsed the `publish` permissions; `scripts/check-repo-rules.sh` exit 0; pre-push `verify-contract.sh` passed. The Gap stays open: `gh attestation verify` has not run on a released ZIP.
