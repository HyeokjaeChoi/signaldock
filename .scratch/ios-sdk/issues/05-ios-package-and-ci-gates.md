# 05. iOS package skeleton and CI gates

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Spec D9, D11, D13. ADR-0047.

1. `sdk-ios/Package.swift`: product and module `SignalDock`, iOS 15, Swift 6 language mode, no external dependency. A placeholder public type with documentation and one test (swift-testing).
2. `docs/design-index.md` section for `sdk-ios/` (repo rule).
3. `repo-rules.yml` jobs on a macOS runner (pinned by SHA): Simulator build and tests under Thread Sanitizer; a separate ASan and UBSan run; `swift format lint --strict` with the documentation rule; warnings as errors; `xcodebuild analyze`. Record which Simulator runtimes the runner has; if iOS 15 is missing, use the fallback in spec section 5 and record it in ADR-0047.
4. CodeQL adds Swift (ADR-0032).
5. `scripts/check-repo-rules.sh`: no `.package(` in `sdk-ios/Package.swift`; no `privacy: .public` under `sdk-ios`. The iOS version has one source (a constant in `sdk-ios`); ticket 13 checks it against the tag. Each new check has a row in the `AGENTS.md` table.
6. `scripts/verify-contract.sh`: Xcode steps run only when `xcodebuild` exists, else print a skip line.

Verification:
- CI run links for every new job; local `xcodebuild` commands and results.
- Each new check fails on a planted violation (show the output), then passes.
