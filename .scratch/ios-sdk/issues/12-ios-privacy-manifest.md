# 12. iOS privacy manifest and required-reason check

Status: ready-for-agent
Type: task
Blocked by: 05

## Scope and completion criteria

Spec D14. ADR-0047.

1. `sdk-ios` package resource `PrivacyInfo.xcprivacy`: tracking false, no domains, `DeviceID` and `ProductInteraction` (not linked, not tracking, Analytics), no required-reason API entries.
2. `scripts/check-repo-rules.sh`: fail when `sdk-ios` sources use `systemUptime`, `mach_absolute_time`, `statvfs`, `statfs`, `volumeAvailableCapacity`, `UserDefaults`, `creationDate`, `modificationDate`, or `stat(` and the manifest has no matching category (`AGENTS.md` row).
3. Check that the built package bundle contains the manifest (verify the SwiftPM `resources:` form against Apple docs first and cite it).

Verification:
- Check output on a planted use, then a pass; a build that shows the manifest in the bundle.
