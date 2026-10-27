# 22. iOS local gates: skip and selection robustness

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor findings from the PR #245 reviews (Claude review and Qodo), recorded under AGENTS.md rule 7.

1. `scripts/verify-ios.sh` `available` and `pick_simulator` use different conditions. `available` passes when any `iOS ` runtime line exists; `pick_simulator` needs an available runtime with an iPhone. With no iPhone device or only an unavailable runtime, the pre-push hook fails instead of skipping (spec D13). Make `available` use the same selection.
2. `pick_simulator` has no lower limit, so an iOS 14 runtime can be picked and `xcodebuild test` fails for the iOS 15 target. Pick the oldest runtime at iOS 15 or later.
3. `available` passes on Xcode versions that cannot build a tools-version 6.0 package or run `swift format` (Xcode 15 or older). Also require Swift 6 and `swift format --version`.
4. `scripts/check-repo-rules.sh`: the `privacy: .public` and `.package(` checks match one line only. A call split across lines (`privacy:` and `.public`, or `.package` and `(`) passes. Match across line boundaries, with a regression sample for each.
5. Nit: the step 8 heading in `scripts/verify-contract.sh` does not name the ASan run.
6. Record the real CI evidence of the first run on PR #245 (repo-rules run 40537476359, CodeQL run 40537476346): the runner runtimes and run links, in ADR-0047 "iOS 15 Simulator fallback" and ADR-0032 (it said "the first CI run decides").

Verification:
- Each case shown failing or skipping as expected (planted sample or simulated PATH), then passing on the clean repo.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
