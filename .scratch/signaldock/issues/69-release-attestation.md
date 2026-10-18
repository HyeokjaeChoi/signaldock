# 69. Build provenance attestation for the release ZIP

Status: claimed
Type: task
Blocked by: None
Execution: Branch `ci/release-attestation`, PR open (not merged)

## Scope and completion criteria

Spec Q167. The decisions are in ADR-0039.

1. The `publish` job of `.github/workflows/release.yml` attests `dist/*.zip` with `actions/attest-build-provenance` (full commit SHA pin) before `gh release create`. Job permissions: `contents: write`, `id-token: write`, `attestations: write`. The `build` job stays `contents: read`.
2. No repository setting changes. The files inside the ZIP are not attested separately (ADR-0039 item 5).
3. Consumer verification (`gh attestation verify <zip> --repo HyeokjaeChoi/signaldock`) is in `docs/releases/README.md`, `README.md`, and `SECURITY.md`.
4. Docs: ADR-0039, spec Q167, `docs/engineering/threat-model.md` (supply-chain and release rows), `CHANGELOG.md`, `docs/design-index.md`.

Verification:
- YAML parse (`yq`) and `actionlint` 1.7.12 on `release.yml`; `scripts/check-repo-rules.sh`.
- Gap: no tag push, so the attestation is not produced until the next release tag. After that tag, run `gh attestation verify` on the released ZIP.
