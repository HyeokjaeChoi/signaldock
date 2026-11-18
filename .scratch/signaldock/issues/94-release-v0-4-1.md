# 94. Release v0.4.1

Status: resolved
Type: task
Blocked by: None
Execution: Done (release PR #313, 2026-11-18 UTC; doc audit PR #314, issue 93)

## Scope and completion criteria

Cut Android SDK v0.4.1 by `docs/releases/README.md` "Cut a release". This is the first release after the ADR-0049 gates, so it is also the first end-to-end run of gate D4 (release needs a green nightly).

1. PR 313 prepared the version lines, `docs/releases/v0.4.1.md`, and `CHANGELOG.md`. PR 314 (issue 93) audited the docs before it.
2. The maintainer tagged the merge commit. The release workflow passed with the nightly gate.
3. The ZIP, its `.sha256` file, and its attestation are checked, and the result is recorded here.

## Comments

- 2026-11-18 UTC: Tag `v0.4.1` on `b5039a8` (merge of PR 313). Nightly run 44233829760 on `b5039a8`: success (all jobs). Earlier, on `9a804f0`: runs 44217170695, 44217206585, and 44217210161, all success.
- Release run 44235853307: `build` success, `publish` success. The step "Nightly is green on this commit" (`scripts/check-nightly-green.sh`) printed `nightly is green on b5039a8b9041583ea9881c404b5296c205412ec8: https://github.com/HyeokjaeChoi/signaldock/actions/runs/44233829760`. Gate D4 passed on a real tag; ADR-0049 has the amendment of 2026-11-18.
- GitHub Release: https://github.com/HyeokjaeChoi/signaldock/releases/tag/v0.4.1 has `signaldock-sdk-android-0.4.1-maven.zip` and `.sha256`. `shasum -a 256 -c` printed OK. A local `scripts/release.sh 0.4.1` gives the SHA-256 `ff2865d2bfe8bd2cbc0b8bd0f4a24611215985cff9225748fc61e87de8e6163f`. `gh attestation verify` with `--repo HyeokjaeChoi/signaldock --signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release.yml` (GitHub CLI 2.102.0) exited 0.
