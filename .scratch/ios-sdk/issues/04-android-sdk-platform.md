# 04. Android SDK 0.4.0 sends `sdkPlatform`

Status: ready-for-agent
Type: task
Blocked by: 01

## Scope and completion criteria

Spec D2, D3. ADR-0046.

1. Every stored event and client report carries `sdkPlatform: "android"`. Events already queued by 0.3.0 keep their stored bytes (ADR-0006 decision 3) and go without the field; the server defaults them (D3).
2. A wire fixture test fails when an event or client report has no `sdkPlatform`.
3. Version 0.4.0 (`SDK_VERSION` and `sdkVersion` stay equal). Public API dump and dependency baseline unchanged, or the change is explained.
4. CHANGELOG entry. Do not tag: the release is ticket 19.

Verification:
- SDK unit tests, `scripts/verify-contract.sh`, `bash scripts/check-repo-rules.sh`.
- `scripts/verify-consumer.sh` against a server from ticket 02.
