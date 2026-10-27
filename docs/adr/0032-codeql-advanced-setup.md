---
status: accepted
---

# CodeQL advanced setup with a manual Kotlin build (issue 54, spec Q162)

The user decided (Q162, 2026-10-16 UTC) to replace the CodeQL default setup with an advanced setup workflow, `.github/workflows/codeql.yml`.

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

## Addition: Swift (iOS SDK ticket 05, ADR-0047 D13 item 6)

`swift` joins the matrix with `build-mode: manual` on a `macos-26` runner (the other languages stay on `ubuntu-latest`). The build is `xcodebuild build` of the `SignalDock` scheme in `sdk-ios` for the iOS Simulator, with `CODE_SIGNING_ALLOWED=NO` and no incremental reuse (a fresh `-derivedDataPath`). The Gradle toolchain step and build step now run for `java-kotlin` only. Same query suite and permissions as above. Gap: the first CI run decides whether the Swift extraction works; it is not run yet.

## Consequences

- The `java-kotlin` analysis covers the main source sets of `sdk-android`, `demo`, `server`, and `contract-check`. Tests are not built, so test code is not analyzed.
- The workflow adds one build of three Gradle projects per run. `timeout-minutes` is 60.

## Triage result (issue 59)

The first `main` run left 51 open alerts (list taken from the code-scanning API on 2026-10-16 UTC). None is a security alert. All are `security-and-quality` rules about code quality.

| Count | Action | Reason |
| --- | --- | --- |
| 14 | Fixed in issue 59 (`perf/*.py`) | 9 `py/file-not-closed`, 2 `py/empty-except`, 2 `py/unused-local-variable`, 1 `py/procedure-return-value-used`. The output of `perf/summarize.py` and `perf/sdk_summarize.py` over the committed results is byte-identical before and after. |
| 23 | Removed with `paths-ignore` for `generated/typescript` (`.github/codeql/codeql-config.yml`; the same file also ignores `tracking-plan/generated/typescript`, the other generated TypeScript output, which had no alerts) | `js/unused-local-variable` in `generated/typescript/models/`. The OpenAPI generator writes these files, so the alerts return after each regeneration. A fix by hand would be lost. |
| 14 | Dismissed in GitHub on 2026-10-16 UTC | See below. |

Dismissed alerts:

- 2 "won't fix": `java/confusing-method-signature` in the generated jOOQ class `Events.java`. The jOOQ generator writes it.
- 1 "used in tests": `java/android/backup-enabled` in `sdk/sdk-android/src/androidTest/AndroidManifest.xml`. Only the instrumented tests use this manifest.
- 11 "false positive":
  - 5 `java/local-variable-is-never-read`: the variables named `tmp0_other_with_cast` come from the Kotlin compiler (generated `equals` code), not from our source.
  - 2 `js/superfluous-trailing-arguments` in `web-shared/i18n/i18n.test.ts`: the type test of `t()` (ADR-0015) passes an extra argument to a stub on purpose.
  - 1 `py/mixed-returns` in `scripts/e2e/fault-proxy.py`: the test proxy returns in mixed ways on purpose.
  - 3 `py/implicit-string-concatenation-in-list` in `perf/summarize.py` and `perf/sdk_summarize.py`: the joined strings are intended.

`paths-ignore` does not apply to the compiled `java-kotlin` analysis (CodeQL documents it for interpreted languages only). So the generated jOOQ alerts are dismissed, not ignored. Gap: a regeneration of the jOOQ classes can raise new alerts for the same rule; dismiss them again.

Result on the PR merge ref (2026-10-16 UTC, PR #158): `gh api "repos/HyeokjaeChoi/signaldock/code-scanning/alerts?state=open&ref=refs/pull/158/merge&per_page=100" --jq length` returned 0 open alerts.

## Gaps

- Gap: the independent consumer app (`compatibility/`, built by `scripts/verify-consumer.sh`) and `sdk/measure-app` are not built, so CodeQL does not analyze them.
- Gap: test source sets are not analyzed.
- The default setup was turned off on 2026-10-16 UTC before PR #148 merged. The first run on `main` (workflow `codeql.yml`, run 38936500321, commit 625cfec) succeeded in all four jobs. The code-scanning API lists `java-kotlin` (8 results), `javascript-typescript` (25), `python` (18), and `actions` (0), all uploaded 2026-10-16 UTC with no error (issue 54).
- Gap: `actionlint` was not run (not installed).
