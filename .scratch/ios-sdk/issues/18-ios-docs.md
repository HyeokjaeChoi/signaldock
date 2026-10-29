# 18. iOS docs

Status: resolved
Type: task
Blocked by: 14, 15
Execution: Done (PR #267, 2026-10-29 UTC)

## Scope and completion criteria

Spec sections 1, 5, 6.

1. `README.md` and `README.ko.md` in step: iOS install from the ZIP, what is out of scope, platform differences and known limits.
2. `docs/integration-guide.md` iOS section: setup, API, opt-out, privacy manifest note for host-defined property data, and the enum rule: minor versions can add cases to public result enums, so use `default` in `switch` (ADR-0047 amendment, 2026-10-28).
3. `docs/reproduce.md`: iOS build, consumer app, durability script, measurement.
4. `CHANGELOG.md`; `architecture.md` decision rows for ADR-0046 and ADR-0047.
5. Doc audit for the changed areas (`docs/agents/doc-audit.md`).

Verification:
- `bash scripts/check-repo-rules.sh`; README commands run from a clean clone (record in the issue file).

## Verification record (2026-10-29)

Environment: macOS 26 (Darwin 25.6.0), Xcode 27.1 (27A9275), Swift 6.4, iOS 27.0 Simulator (iPhone 17), Docker 29.8.2. The commands ran in a clean `git worktree` of commit `6d334d3` (the first commit of branch `ios/18-docs`, on top of `origin/main` `ad3354e`). Compose project `signaldock-ios-<time>` with ports 18080/15432/15173 and a private env file; `down -v` done; the running project `signaldock-phone` was not touched.

| Command | Result |
| --- | --- |
| `scripts/check-repo-rules.sh` (staged tree) | exit 0 |
| `scripts/verify-ios.sh available` | exit 0 |
| `scripts/build-ios-release-zip.sh 0.1.0`, then `shasum -a 256 -c` | exit 0; `signaldock-sdk-ios-0.1.0.zip: OK` (local hash `ee85713f...e36c3c`) |
| `IOS_WERROR=1 scripts/verify-consumer-ios.sh` | exit 0; Debug and Release `BUILD SUCCEEDED`; the only `warning:` lines are the Xcode tool message "Metadata extraction skipped, no AppIntents.framework dependency found" |
| Compose stack, `simctl boot/install`, launch with `SIMCTL_CHILD_SIGNALDOCK_AUTORUN=1` (the block in `docs/reproduce.md`) | the query API with `sdkPlatform=ios` returned `product_viewed`, `add_to_cart`, `checkout_started`, `purchase_completed`, all with `sdkVersion` `0.1.0`; Dashboard `http://127.0.0.1:15173/` answered 200 (not rendered in a browser) |
| `IOS_WERROR=1 scripts/simulator-durability.sh <udid> offline restart` with `SIGNALDOCK_ENDPOINT` | exit 0; `ok: offline` (30 of 30), `ok: restart` (5 of 5); backup restore and full disk printed `not run` |
| `IOS_WERROR=1 scripts/verify-ios.sh lint` | exit 0 |
| `IOS_WERROR=1 scripts/verify-ios.sh manifest` | exit 0; `PrivacyInfo.xcprivacy: OK` |
| `scripts/verify-ios.sh api` | skip line: the API file is from Swift 6.3 (CI compiler), this Mac has Swift 6.4 (documented behavior) |
| Swift samples of the integration guide and the README, `swiftc -typecheck` against the built module (iOS 15 target, Swift 6 mode) | exit 0 |
| Relative links of the changed Markdown files | all exist |

Did not run: `scripts/verify-ios.sh tsan`, `asan`, `analyze` (not changed by this ticket; they are described from the script), `scripts/verify-contract.sh`, the unchanged Android and server steps of the README, `gh attestation verify` for an iOS ZIP (no release), the measurement script (ticket 16 is not merged: `docs/reproduce.md` says "measurement: see ticket 16 (pending)").

## Doc audit (`docs/agents/doc-audit.md`)

Areas: README and README.ko, integration guide and Korean guide, reproduce, CHANGELOG, architecture decisions table. Fixed in this ticket: the "iOS / Flutter" out-of-scope wording in both READMEs; "No iOS or Flutter SDK" in both guides; the repository guide table and the CI job list (iOS jobs added); the Stack table. Design-source mismatches that were not changed (reported to the coordinator):

- `docs/adr/0047-ios-sdk.md` Context ("No iOS code exists. Everything in this ADR is a design.") and "Not verified here" ("Everything. No iOS code exists yet.") are stale: the results sections show built and tested code.
- `docs/adr/0047-ios-sdk.md` D14 shows `resources: [.process("PrivacyInfo.xcprivacy")]`; `sdk-ios/Package.swift` also has `.copy("Migrations")`.
- `docs/adr/0046-sdk-platform-field.md` ("Not verified here: no code exists yet") and `.scratch/ios-sdk/spec.md` ("No product code exists yet") are stale.
- `architecture.md` components, lifecycle, and distribution sections describe the Android SDK only (Room, WorkManager, Maven ZIP); only the decisions table has iOS rows.
- ADR-0047 "Ticket 13 results": the release workflow needs `docs/releases/ios-v0.1.0.md`; the file does not exist yet (ticket 19).
