# 20. Issue 15 review findings left open (Nit)

Status: claimed
Type: task

Found by the local review of the issue 15 branch. None blocks a gate.

1. (Nit) Root `package.json` `overrides` pins `minimatch` 11.0.8 for `@typescript-eslint/typescript-estree`, outside its declared range `^10.2.2`. Remove the override when typescript-eslint itself asks for minimatch 11.0.1 or later. Until then, Renovate does not update the pin.
2. (Nit) `scripts/update-gradle-verification.sh` only adds entries, so each AGP bump leaves stale components and OSV findings in `verification-metadata.xml`. Consider making the script empty the `<components>` block first (ADR-0038 Gaps), in its own PR.

## Result

1. Done. typescript-eslint 8.73.0 (`@typescript-eslint/typescript-estree` 8.73.0) asks for `minimatch` `^11.0.1`. The root `package.json` pins 8.73.0 and has no `overrides`. `npm run lint` and `npm run format:check` pass; `scripts/osv-scan.sh` finds nothing in the three npm lockfiles.
2. Done. `scripts/update-gradle-verification.sh` empties the `<components>` block of the four files before the run (the `<configuration>` block stays); ADR-0038 Gaps says so. A full run with an empty `<components>` block (all builds green, Docker for `verifyJooq`) rewrote the four files byte for byte as before, so no stale entry was left after the issue 15 prune.
3. No `[[IgnoredVulns]]` entry can go now. `ignoreUntil` stays 2026-12-16. Checked 2026-11-17:
   - AGP 9.5.1 is the newest stable (9.6.0 is alpha). Its POMs (`gradle`, `builder`) still ask for bcpkix/bcprov 1.80.2 and httpmime 4.5.6, so a bump fixes none of the AGP entries; it also stays a Renovate PR.
   - Macrobenchmark 1.5.0 is the newest stable (1.6.0 is alpha). `wire-runtime` is fixed in 6.4.5 and the `okio` .module comes from moshi 1.13.0 through the same tool.
   - Robolectric 4.18 exists, but the AGP classpath keeps Guava 33.4.0 (fixed in 33.7.2), so the Guava entry stays.

| File | Entry | Fix path |
|---|---|---|
| `sdk/gradle/`, `compatibility/consumer-android/gradle/` | guava, commons-lang3, httpclient, jose4j, bcpkix, bcprov (2), jdom2 | AGP release that moves these tool dependencies |
| `compatibility/consumer-android/gradle/` | kotlin-gradle-plugin 2.2.10 (GHSA-r937-wjx7-w2jp) | AGP built-in Kotlin update |
| `sdk/gradle/` | okio 2.10.0 .module, wire-runtime 6.4.0 | stable Macrobenchmark with moshi/wire at fixed versions |

Renew an entry on 2026-12-16 only if its reason is still true.
