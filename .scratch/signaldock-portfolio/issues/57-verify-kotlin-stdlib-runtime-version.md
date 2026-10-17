# 57. Verify the runtime kotlin-stdlib version against the POM

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #157, 2026-10-17 00:00:37 UTC)

## Scope

Spec Q132 and ADR-0009 decision 4: the SDK POM pins `kotlin-stdlib` to 2.2.21 so Kotlin 2.2 consumers can read the AAR. The dependency baseline of issue 56 (ADR-0033, `sdk/sdk-android/dependencies/`) shows that the runtime classpath resolves `kotlin-stdlib` to 2.3.20.

Question: is the 2.3.20 only Gradle resolution inside this repo (for example the Kotlin plugin or a transitive constraint), or does a host app also get 2.3.20 from the published POM? Check both the baseline and the generated POM, and decide whether Q132 still holds.

Result: Q132 holds. See the 2026-10-16 comment and ADR-0009 "Check of decision 4".

## Comments

- 2026-10-16 UTC: opened from issue 56 (PR #147).
- 2026-10-16 UTC: checked. Q132 holds; docs only (ADR-0009 "Check of decision 4", spec Q132 note). Commands (JAVA_HOME = `.tools/jdk-21.0.12.1+1`, ANDROID_HOME = `~/Library/Android/sdk`):
  1. `sdk/gradlew -p sdk :sdk-android:dependencyInsight --configuration releaseRuntimeClasspath --dependency kotlin-stdlib`: result `kotlin-stdlib:2.2.21 -> 2.3.20`, selection reasons "By constraint" and "By conflict resolution".
  2. Cause: the module metadata of `kotlinx-serialization-core-jvm` 1.11.0 (Gradle cache) has `kotlin-stdlib` `requires` 2.3.20 in both variants.
  3. `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip`: BUILD SUCCESSFUL. POM: `kotlin-stdlib` 2.2.21 (compile scope). `.module`: `requires` 2.2.21 in both variants.
  4. `scripts/verify-consumer.sh` (exit 0): `metadata versions: mv=[2,2,0]`, `consumer Kotlin Gradle plugin: kotlin-gradle-plugin:2.2.10`, consumer `assembleRelease` (R8 9.3.28) BUILD SUCCESSFUL.
  5. Consumer `:app:dependencyInsight --dependency kotlin-stdlib`: `releaseCompileClasspath` = 2.2.21; `releaseRuntimeClasspath` = 2.3.20.
  Gap: no emulator run in this check.

- 2026-10-17 UTC: PR #157 merged 00:00:37Z (docs only; ADR-0009 "Check of decision 4", spec Q132 note). Q132 holds. Review: CodeRabbit gave no actionable comments on the last reviewed commit. Its pre-merge check "Verification Evidence" warned that metadata inspection and consumer commands were not given in full; this was not applied (PR #157 description lists the commands).
- Gap: no emulator run. Gap: a host app that adds kotlinx-serialization 1.11.0 to its own compile classpath gets stdlib 2.3.20 at compile time (PR #157).
