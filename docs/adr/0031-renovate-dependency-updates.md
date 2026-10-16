---
status: accepted
---

# Renovate for dependency updates (issue 52)

Spec Q160 replaces Dependabot version updates with Renovate. This ADR supersedes ADR-0024 items 5 and 6 and the Dependabot part of its 2026-10-16 amendment. ADR-0024 items 1 to 4 (release workflow) stay. The user can change these choices.

## Why

Dependabot cannot read the shared catalog `gradle/libs.versions.toml`. Four builds (`/sdk`, `/server`, `/contract-check`, `/compatibility/consumer-android`) load it with `from(files("../gradle/libs.versions.toml"))`. Dependabot needs a `build.gradle(.kts)` in the update directory and reads a catalog only at `<dir>/gradle/libs.versions.toml` (dependabot-core issue 8079). So it saw no catalog version. Renovate's `gradle` manager matches `gradle/*.toml` and `*.versions.toml` at any path, so it sees the catalog. Other Android and Square projects use it for the same reason (`android/nowinandroid`, `square/okhttp`, `square/retrofit`, `cashapp/sqldelight`).

## Decisions

1. **Config: `.github/renovate.json5`.** It extends `config:recommended`, `helpers:pinGitHubActionDigests`, and `docker:pinDigests`. Actions stay pinned to a commit SHA with the tag in a comment. Docker and Compose images stay pinned by digest. Schedule: before 06:00 UTC on Monday. `prConcurrentLimit` is 4. The Dependency Dashboard is on. `group:monorepos` is ignored, because it names groups that would override the groups below.
2. **Managers.** `gradle` (catalog, plugins), `gradle-wrapper`, `npm` (only `/dashboard` and `/perf/report`), `github-actions`, `dockerfile`, `docker-compose`. The Gradle wrapper is new: Dependabot did not update it.
3. **Google Maven.** A rule gives `androidx.**`, `com.android.**`, and `com.google.**` the registries `https://maven.google.com`, Maven Central, and the Gradle plugin portal. Other Gradle packages use Maven Central and the plugin portal. The AGP plugin ids (`com.android.library`, `com.android.application`) resolve on Google Maven.
4. **Groups.** The catalog is shared, so groups follow the package, not the directory. `sdk-consumer-facing`: kotlinx, Room, WorkManager, Square (not the Kotlin compiler and plugins, which are build-only: the POM's `kotlin-stdlib` comes from `coreLibrariesVersion` in `sdk/sdk-android/build.gradle.kts`, spec Q132 and ADR-0009, and is raised by hand; and not the test-only artifacts `work-testing`, `mockwebserver3`, `kotlinx-coroutines-test`, and not the API-check plugin). `server`: Ktor, jOOQ, Flyway, Hikari, PostgreSQL driver, Testcontainers, SLF4J. `build-and-test`: all other Gradle packages and the Gradle wrapper. npm: `npm-build-and-test` (dev) and `npm-runtime`. `actions`, `docker-images`, `compose-images`. Where one catalog version is shared by packages of two groups, the branch can take the shared version name (`renovate/flyway`); the PR is still one update.
5. **Consumer-facing label.** The `sdk-consumer-facing` group changes the published POM. Its PRs get the label `sdk-consumer-facing` and a PR-body note: add a line to `CHANGELOG.md` before merge (`docs/releases/README.md`).
6. **No automatic majors.** Docker images (JDK 21 LTS, Node 24 LTS, nginx), the Compose PostgreSQL image (a major needs dump and restore), and npm `@types/node` (follows Node 24; `TOOLCHAIN.md`). Digest, minor, and patch updates still come. A runtime major is a manual decision with its own PR.
7. **`pr-description.yml`.** The `length` job skips `dependabot[bot]` and `renovate[bot]`. Both write release notes into the body.
8. **`.github/dependabot.yml` is deleted.** No Dependabot PRs remain: version updates and security updates are both off.
9. **Security fixes (spec Q161).** GitHub Dependabot alerts are on (a repository setting, 2026-10-16 UTC). They raise no PR. Renovate reads them (`vulnerabilityAlerts`, label `security`, own budget of 5 PRs, no schedule, no release-age delay, no automerge); the Renovate app needs read access to Dependabot alerts. GitHub does not parse Gradle builds, so for Gradle Renovate also checks OSV (`osvVulnerabilityAlerts`). OSV sees only the declared dependencies, not the transitive tree. Gradle dependency submission was rejected (2026-10-17 UTC): none of the 12 surveyed SDK repos uses it.
10. **Release age.** `minimumReleaseAge` is 3 days, as the Dependabot `cooldown` of Mixpanel (30 days), PostHog (7) and RevenueCat (3). `minimumReleaseAgeBehaviour` is `timestamp-optional`, so a registry without release timestamps does not block updates. Security fixes are not delayed.

## Accepted costs

- Renovate cannot read the pin reasons in catalog comments, so each documented pin has a `packageRules` entry with `allowedVersions` and a `description` that names its source. Pin mapping:

  | Catalog entry | Rule | Source |
  | --- | --- | --- |
  | `okhttp` (`okhttp`, `mockwebserver3`) | `<5.5.0` | 5.5.0 needs compileSdk 37; the SDK builds at 36. ADR-0006 decision 1, TOOLCHAIN.md |

  Not pinned by a rule, because no source gives a version limit: KSP 2.3.12 (ADR-0003 item 1 says only that it is the latest release), Retrofit 3.0.0 and WorkManager 2.12.0 (ADR-0006 says only that they stay, with no limit), `demo-compileSdk` 37 (the demo app builds at 37, so the demo AndroidX versions need no limit), and the Kotlin 2.2 consumer metadata (set by compiler options, not by a catalog version). If a later update breaks the build at compileSdk 36, add a rule in the same PR as the fix.
- The shared catalog also lists `jdk`, `gradle`, `compileSdk`, `minSdk`, and `targetSdk`. They are not dependencies, and Renovate ignores them.

## Gaps

- Gap: the config was validated locally only (`renovate-config-validator` and a dry run, see issue 52). The Renovate app is not installed on the repository. The first real run log must show that the catalog is detected and that Google Maven lookups work.
- Gap (accepted, Q161 amended): GitHub sees no Gradle dependency, because dependency submission was rejected. Gradle vulnerability fix PRs come from Renovate's OSV check of the declared dependencies only; a vulnerable transitive Gradle dependency raises no alert.
- Gap: the local dry run read the config as global config (`RENOVATE_CONFIG_FILE`). Renovate's local platform does not read `.github/renovate.json5` by itself.
