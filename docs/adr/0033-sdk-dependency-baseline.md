---
status: accepted
---

# SDK published dependency baseline (issue 56)

Spec Q162 is decided in the CodeQL PR (issue 54). This ADR records how the SDK dependency tree is guarded.

## Context

- A host app gets the transitive dependencies of the SDK, not only the direct ones. A version bump in the catalog (or by a Renovate PR, ADR-0031) can change that tree without any change in the SDK source.
- `apiCheck` (ADR-0019) guards the public API only. Nothing guarded the dependency tree.

## Decision

1. **Plugin.** Use the Dropbox `com.dropbox.dependency-guard` plugin 0.5.0 (catalog key `dependency-guard`) in `:sdk-android`. It needs Gradle 7.4 or newer. I ran it on Gradle 9.7.0, AGP 9.3.3, and Kotlin 2.4.21, and it works with no workaround. `android/nowinandroid` uses the same plugin.
2. **Baselines.** `sdk/sdk-android/dependencies/releaseRuntimeClasspath.txt` (what the AAR and POM give a consumer at runtime) and `releaseCompileClasspath.txt` (what a consumer compiles against). Each line is `group:artifact:version`, sorted.
3. **CI.** Step 6 of `scripts/verify-contract.sh` runs `:sdk-android:dependencyGuard` after `apiCheck`. It runs in CI and in the pre-push hook. It fails with a list of added and removed lines.
4. **Update.** `sdk/gradlew -p sdk :sdk-android:dependencyGuardBaseline` rewrites both files. A baseline change in a PR is a change a consumer can see: add a `CHANGELOG.md` line in the same PR. This matches the Renovate `sdk-consumer-facing` label (ADR-0031).

## Rejected

An in-repo Gradle task or script that normalizes `:sdk-android:dependencies --configuration releaseRuntimeClasspath` into a text file and diffs it. It has no new dependency, but we would own the parser and the diff output. The plugin gives both, and it works.

## Limits

- Gap: the plugin 0.5.0 was released in 2024-02. If it stops working with a new Gradle or AGP, switch to the rejected task. The baseline files stay the same shape.
- The baseline covers the SDK only. The server and the demo app are not guarded.
- It lists the resolved version, not hashes. Dependency verification (checksums) is not part of this change.
