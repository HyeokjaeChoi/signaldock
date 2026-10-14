---
status: accepted
---

# SDK public API check (issue 35)

The SDK public API is `local.signaldock` in `sdk-android` (`SignalDock.kt`, `Results.kt`, `EventBuilder.kt` and the types they use). Issue 34 documented it. Nothing stopped a change from breaking an app that uses it. These decisions add a check. They are implementer decisions that the user can change. They add no product behavior and change no product code.

## Decisions

1. **The tool is the `binary-compatibility-validator` 0.18.2 task classes, wired by hand.** Two ready-made options did not work with this build (AGP 9.3.3 with built-in Kotlin 2.4.21):
   - The built-in Kotlin Gradle plugin check (`kotlin { abiValidation() }`, tasks `updateKotlinAbi` and `checkKotlinAbi`) ran but dumped nothing: its input is empty for an AGP library, and `referenceDumpDir` stayed empty.
   - The `org.jetbrains.kotlinx.binary-compatibility-validator` plugin registered no tasks. It looks for a Kotlin plugin id (`kotlin-android`), and AGP built-in Kotlin applies none.

   So `sdk/build.gradle.kts` applies the validator plugin (version in `gradle/libs.versions.toml`) only to put its task classes on the build classpath. `sdk/sdk-android/build.gradle.kts` registers `apiBuild` (`KotlinApiBuildTask`, input: the `compileReleaseKotlin` output), `apiCheck` (`KotlinApiCompareTask`) and `apiDump` (a `Copy` task). The dump format is the validator's standard `.api` text, which is easy to review in a diff. Kotlin `internal` declarations are not in the dump. Revisit when the Kotlin built-in check supports AGP libraries: then delete the hand-wired tasks and the plugin.
2. **The dump is `sdk/sdk-android/api/sdk-android.api`, committed.** It comes from the release variant.
3. **The check runs in `scripts/verify-contract.sh` (step 6).** That script runs in the pre-push hook and in the CI job `verify-contract`, so no new CI job is needed. The step compiles the release variant, so it needs `ANDROID_HOME` (CI has it).
4. **Update the dump on purpose with `sdk/gradlew -p sdk :sdk-android:apiDump`.** Review the diff of `sdk-android.api`. In the same PR, update the integration guide (`docs/integration-guide.md` and `.ko.md`) and `CHANGELOG.md`. An API change that is not in the dump diff is a mistake.

## Limits

- The check shows binary API changes, not behavior changes. KDoc and the guide still need review.
- `apiCheck` fails on any difference, including a compatible addition. This is deliberate: every API change is visible in the PR.
