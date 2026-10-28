# 13. iOS release ZIP and workflow

Status: claimed
Type: task
Blocked by: 08, 12

## Scope and completion criteria

Spec D11. ADR-0009, ADR-0024, ADR-0039, ADR-0047.

1. A script builds `signaldock-sdk-ios-<version>.zip` (the Swift package folder with `Package.swift`, sources, resources, license) and its SHA-256 file from a clean folder.
2. `.github/workflows/release-ios.yml` on `ios-v*.*.*` tags only: build the ZIP, attest provenance, create the GitHub Release. Top-level `permissions:`, pinned actions, no `pull_request_target` (repo rules). The Android `release.yml` does not run on these tags.
3. The tag version must equal the iOS version constant.

Verification:
- Local ZIP build twice gives the same SHA-256 (or the difference is explained).
- Workflow lint by the repo rules; the real tag run is ticket 19.
