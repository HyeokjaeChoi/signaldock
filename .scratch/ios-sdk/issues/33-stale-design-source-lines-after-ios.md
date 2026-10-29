# 33. Design sources: stale "no iOS code" lines and Android-only architecture sections

Status: ready-for-agent
Type: task
Blocked by: None

Found by the ticket 18 doc audit (`.scratch/ios-sdk/issues/18-ios-docs.md`, "Doc audit"). Design sources are not edited by a doc audit (`docs/agents/doc-audit.md`, "Who decides"); this issue does it under AGENTS.md judgment rule 2.

## Scope and completion criteria

1. `docs/adr/0047-ios-sdk.md`: Context ("No iOS code exists. Everything in this ADR is a design.") and "Not verified here" ("Everything. No iOS code exists yet.") get a dated status line that points to the results sections; the decision text stays.
2. `docs/adr/0047-ios-sdk.md` D14: the `resources` example also names `.copy("Migrations")`, as `sdk-ios/Package.swift`.
3. `docs/adr/0046-sdk-platform-field.md` "Not verified here" and `.scratch/ios-sdk/spec.md` status ("No product code exists yet"): dated status lines.
4. `architecture.md` components, lifecycle, and distribution sections: add the iOS SDK (SQLite queue, delivery triggers, release ZIP), citing ADR-0047.
5. Release 0.4.0 prep (found in PR review): the `architecture.md` and `docs/architecture/onepager-content.md` (and `signaldock-onepager.html`) lists of stored event fields and the duplicate comparison omit `sdkPlatform` (ADR-0046); add it, then raise the "Updated for SDK" stamp to 0.4.0.

Verification:
- `scripts/check-repo-rules.sh`; every cited path exists.
