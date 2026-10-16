# 57. Verify the runtime kotlin-stdlib version against the POM

Status: needs-triage
Type: task
Blocked by: None
Execution: Not started

## Scope

Spec Q132 and ADR-0009 decision 4: the SDK POM pins `kotlin-stdlib` to 2.2.21 so Kotlin 2.2 consumers can read the AAR. The dependency baseline of issue 56 (ADR-0033, `sdk/sdk-android/dependencies/`) shows that the runtime classpath resolves `kotlin-stdlib` to 2.3.20.

Question: is the 2.3.20 only Gradle resolution inside this repo (for example the Kotlin plugin or a transitive constraint), or does a host app also get 2.3.20 from the published POM? Check both the baseline and the generated POM, and decide whether Q132 still holds.

Gap: I have not run this check. This issue records the observation only.

## Comments

- 2026-10-16 UTC: opened from issue 56 (PR #147).
