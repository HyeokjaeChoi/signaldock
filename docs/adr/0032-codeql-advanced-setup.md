---
status: accepted
---

# CodeQL advanced setup with a manual Kotlin build (issue 54, spec Q162)

The user decided (Q162, 2026-10-17 UTC) to replace the CodeQL default setup with an advanced setup workflow, `.github/workflows/codeql.yml`.

## Context

- The default setup was on. It detected `actions`, `javascript-typescript`, `python`, and `java-kotlin`.
- The first run (Actions run 38918222644) failed for `java-kotlin`. The autobuild log said: "Cannot auto-detect a single build directory. Candidates : ./contract-check,./sdk,./server", then "Could not detect a suitable build command".
- GitHub documents that autobuild looks in the root, then in direct subdirectories. If more than one subdirectory has build files, it fails. This repo has three independent Gradle builds (ADR-0005) and no root build.
- `build-mode: none` does not extract Kotlin ("Kotlin analysis requires a build"). So `java-kotlin` needs a `manual` build.
- Public Android SDK repos do this: `getsentry/sentry-java` runs `./gradlew buildForCodeQL --no-build-cache`; `DataDog/dd-sdk-android` runs `./gradlew assembleLibrariesRelease`; `PostHog/posthog-android` uses a matrix of `java-kotlin` and `actions` with `queries: security-and-quality`.

## Decision

1. Advanced setup in `.github/workflows/codeql.yml`. Triggers: push to `main`, pull request to `main`, weekly schedule (Monday 04:23 UTC).
2. Matrix: `java-kotlin` (`build-mode: manual`), and `javascript-typescript`, `python`, `actions` (`build-mode: none`). `fail-fast` is off, so one language does not hide another.
3. Manual build, after `scripts/setup-toolchain.sh` and the JDK from `.tools` (the same setup as `repo-rules.yml`; the runner's Android SDK is at `$ANDROID_HOME`):
   - `sdk/gradlew -p sdk :sdk-android:assembleRelease :demo:app:assembleDebug`
   - `server/gradlew -p server classes`
   - `contract-check/gradlew -p contract-check classes`

   All use `--no-daemon` (CodeQL cannot trace a build that an existing daemon runs) and `--no-build-cache` (a cache hit skips the compile tasks, so CodeQL sees no source). They also set `-Dorg.gradle.jvmargs` with a 3 GiB heap: the first PR run failed with "garbage collector is thrashing" in `server:persistence:compileKotlin`, because the CodeQL Kotlin extractor runs inside the compiler and the server build uses the 512 MiB Gradle default.
4. The release SDK is analyzed because it is the shipped artifact. The demo app is included because it compiles with the SDK build at small cost and is sample code a reader copies. `server` and `contract-check` use `classes` (main source sets only).
5. Queries: `security-and-quality`, not the default suite. Reason: this is a portfolio repo with a small code base, so the extra quality alerts are cheap to read, and PostHog uses the same suite. If the alerts become noise, go back to the default.
6. Permissions: `contents: read` at workflow level; the job adds `security-events: write` and `actions: read`.
7. Every action is pinned by full commit SHA with a tag comment (like the other workflows).
8. The default setup must be turned off when this workflow lands. GitHub rejects SARIF from an advanced setup while the default setup is on. The repository owner does this at merge time: `gh api -X PATCH repos/HyeokjaeChoi/signaldock/code-scanning/default-setup -f state=not-configured`. This PR does not change repository settings.

## Consequences

- The `java-kotlin` analysis covers the main source sets of `sdk-android`, `demo`, `server`, and `contract-check`. Tests are not built, so test code is not analyzed.
- The workflow adds one build of three Gradle projects per run. `timeout-minutes` is 60.

## Gaps

- Gap: the independent consumer app (`compatibility/`, built by `scripts/verify-consumer.sh`) and `sdk/measure-app` are not built, so CodeQL does not analyze them.
- Gap: test source sets are not analyzed.
- Gap: the first run on `main` must show `java-kotlin` results in the Security tab. Until then, CodeQL results are not a verified claim. The PR run cannot upload results while the default setup is on (expected).
- Gap: `actionlint` was not run (not installed).
