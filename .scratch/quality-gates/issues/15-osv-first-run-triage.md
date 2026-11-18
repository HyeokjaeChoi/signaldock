# 15. Triage of the first OSV-Scanner run (55 ignored advisories)

Status: resolved
Type: task
Execution: Done (PR #306, 2026-11-17 UTC). The triage and the entries that stay are in issue 20; renew or remove each entry on its `ignoreUntil` date.

Ticket 11 (ADR-0049 decision 9, gate R2) ignores 55 advisories in three `osv-scanner.toml` files (root, `sdk/gradle/`, `compatibility/consumer-android/gradle/`; one per tree, review finding of PR 295) until **2026-12-15** (30 days). Every entry fails the PR and nightly gate again on that date.

1. Gradle build classpath (54 advisories, `sdk/` and `compatibility/consumer-android/` `gradle/verification-metadata.xml`): Netty 4.1.93 and 4.1.110, Bouncy Castle 1.79 and 1.80.2, jose4j, jdom2, httpclient, commons-lang3, Guava, okio 2.10.0, wire-runtime 6.4.0, and `kotlin-gradle-plugin` 2.2.10. They come from the Android Gradle Plugin and the Kotlin plugin. None is in `sdk/sdk-android/dependencies/` (the release classpath) or in `server/` or `contract-check/` (no finding). Find which plugin pulls each one: `sdk/gradlew -p sdk buildEnvironment`. Fix by the AGP, Gradle, or Kotlin update that Renovate proposes (AGP 9.4.1 is on the Dependency Dashboard), then delete the entries that no longer match.
2. `minimatch` 10.2.6 (GHSA-g3hv-75wg-m249), a dev dependency of ESLint in the root `package-lock.json`. The fix is 11.0.1, a new major; check whether `npm` overrides or an ESLint update allow it.
3. For each advisory that stays after the first fix round, renew its entry in a PR that says why, or accept it with an ADR-0049 amendment. Do not renew by date only.

Done when no `osv-scanner.toml` has an entry without a written reason that is still true, or no entry at all.
