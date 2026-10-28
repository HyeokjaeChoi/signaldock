# 19. Release Android 0.4.0 and iOS 0.1.0

Status: ready-for-human
Type: task
Blocked by: 02, 03, 04, 10, 15, 17, 18

## Scope and completion criteria

Spec section 4.

1. The server and Dashboard with `sdkPlatform` support are deployed, including the cloud demo (`demo-v*` tag, ADR-0043), before any SDK tag.
2. The owner approves and pushes `v0.4.0` and `ios-v0.1.0`.
3. Both GitHub Releases have the ZIP, SHA-256, and a verified attestation (`gh attestation verify`).
4. Refresh the Android docs for 0.4.0, as the 0.3.0 release PR `97abd80` did: `docs/integration-guide.md` (version lines 9, 36, 54, 57, 362; the event-field table near line 298 gains `sdkPlatform`), `README.md` and `README.ko.md` (lines 87, 224, 227 and the equivalents), and the default version in `compatibility/consumer-android/app/build.gradle.kts`.
5. Record what ran in this file.

Verification:
- Release links, `gh attestation verify` output, one consumer install from each downloaded ZIP.
