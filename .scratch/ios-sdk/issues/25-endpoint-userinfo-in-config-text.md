# 25. Config text shows credentials in an endpoint URL

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR #255 Qodo review, recorded under AGENTS.md rule 7. It applies to both SDKs.

`validateConfig` accepts an endpoint with user information (`https://user:pass@host`). The config text prints the full endpoint: iOS `SignalDockConfig.description` and `customMirror`, and Android `SignalDockConfig.toString()` (`Results.kt`). A host that logs its config then logs the password.

1. Decide with the owner: reject an endpoint with user information in `validateConfig`, or mask it in the config text. Record the decision in ADR-0047 and the Android design source.
2. Apply the same rule on both platforms, with a test on each.

Verification:
- `scripts/verify-ios.sh tsan` and `./gradlew :sdk-android:testDebugUnitTest` pass.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
