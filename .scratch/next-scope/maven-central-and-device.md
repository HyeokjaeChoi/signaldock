# Maven Central and physical device: research note (v0.3.0)

Read-only. Paths relative to worktree root. "Ext" = outside repo, not verified here.

## A. Maven Central

### Reuse
- Publication `release`, group `local.signaldock`, artifact `sdk-android`, version `0.3.0`: `sdk/sdk-android/build.gradle.kts:70-78`.
- Sources jar only: `build.gradle.kts:32`. No javadoc jar.
- Folder repo + ZIP task: `build.gradle.kts:88-103`.
- Tag workflow, two jobs, attestation, GitHub Release only: `.github/workflows/release.yml:4-8, 56-81`.
- Attestation verified for v0.3.0: `docs/adr/0039-release-provenance-attestation.md:40`.
- API check keys on Kotlin package, not group: `build.gradle.kts:137-153`; `sdk/sdk-android/api/sdk-android.api:1`.

### Gaps for Central
- No `signing {}` and no `pom {}` (name, url, license, scm, developers): `build.gradle.kts:72-87`.
- No javadoc jar (Ext: Central requires it).
- Scope: spec excludes Central (`.scratch/signaldock/spec.md:17`) and says `local.signaldock` is "not a claim of external namespace ownership" (`spec.md:245`). `docs/versioning.md:52` repeats "not on Maven Central".
- Central needs a verified namespace, e.g. `io.github.<user>` (Ext). Owner must claim it.
- GPG key and Central token go in CI secrets (Ext). Owner only.
- Stale: `docs/adr/0009-maven-zip-and-consumer-app.md:11-12` says `0.1.0`; build says `0.3.0`.

### Does a group change break things?
- groupId only, package kept `local.signaldock`: public API dump unchanged (it lists classes, `api/sdk-android.api:1`).
- groupId change breaks consumers and gates; sweep in one PR:
  - `compatibility/consumer-android/app/build.gradle.kts:62` (coordinate).
  - `compatibility/consumer-android/settings.gradle.kts:21` (`includeGroup`).
  - Trust: `compatibility/consumer-android/gradle/verification-metadata.xml:7`; `sdk/gradle/verification-metadata.xml` (ADR-0038 item 4, `docs/adr/0038-gradle-dependency-verification.md:24`). Rerun `scripts/update-gradle-verification.sh`.
  - Docs: `README.md:79,144,216`; `README.ko.md:81,146,218`; `TOOLCHAIN.md:84`.
  - `scripts/verify-consumer.sh:42` hard-codes `local/signaldock/sdk-android`.
- Changing package (`build.gradle.kts:13` namespace) is a breaking API change (ADR-0019). Do not do it.

### Who must act
- Owner: claim namespace, create GPG key and token, pick new groupId, approve scope change (spec excludes Central).
- Agent: pom/signing/javadoc config, workflow step (test on staging or local repo), coordinate sweep, consumer verify.

### Rough size
- Agent: 1 to 2 days. Owner: 1 to 3 hours plus namespace verification wait (Ext).

## B. Physical device

### Already done
- Phone instrumented tests, 17 pass, Galaxy Z Flip7, API 37: `.scratch/signaldock/issues/85-device-verification-v0.3.0.md:13,22`.
- Phone `device-measure.sh` (3 scenarios) and `host-cost-measure.sh`: `issues/85:23-24`; results `perf/sdk-results/api37-phone-*/`, `perf/sdk-results/host-cost-phone-api37/`.
- Opt-in: `SIGNALDOCK_PHYSICAL_DEVICE=1`, ADR-0042 decisions 1-7 (`docs/adr/0042-measurement-on-a-physical-device.md:16-23`); `docs/reproduce.md:75-89`.

### Not run on phone (`issues/85:55-60`)
1. `scripts/device-durability.sh`: refused by code (`scripts/device-durability.sh:23`), by design (ADR-0042 decision 8, line 24; ADR-0029 line 16).
2. Release R8 measurement: refused, needs `adb root` (ADR-0042 decision 3, line 18; `docs/adr/0018-sdk-release-r8-measurement.md:41`). Partly covered: host-cost installs R8 release consumer apps.
3. Baseline Profile benchmark on phone: only emulator `faults37` used (`docs/adr/0040-sdk-baseline-profile.md:23,49`). Plugin needs one booted device (ADR-0040 decision 6, line 23).
4. Fault and durability phase cases (`-e phase`): `issues/85:60`. Power loss untested anyway (`docs/adr/0029-device-fault-tests-and-power-loss.md:14`).

### Data gaps
- Phone results lack serial and build fingerprint: `issues/85:53`.
- Phone `track()` p50 is 32 ms vs 1.6 ms on emulator: `issues/85:50`. Not comparable (ADR-0042 Consequences, line 26).
- Stale doc: `docs/adr/0018-sdk-release-r8-measurement.md:35` says "No physical device".

### Who must act
- Owner (phone needed): USB, unlock, awake, accept debug prompt; run item 3 if wanted. Items 1 and 4 change backup/airplane/disk on a personal phone: owner decides; agent should not change the guard without a new ADR.
- Agent (no phone): add serial/fingerprint to phone `meta.json`; write Baseline Profile phone command and result template; fix stale docs.

### Risks
- Test packages stay on phone after failed run (ADR-0042 Consequences). Owner removes them.
- Thermal state and other apps change numbers (`docs/reproduce.md:89`).

### Rough size
- Agent: 0.5 day. Owner: 1 to 2 hours for item 3.

## Summary
- A: Central is new work and owner-gated (namespace, keys, scope). Keeping the package avoids an API break; a groupId change is a coordinate sweep.
- B: Most phone work is done (issue 85). Only item 3 is a realistic phone run; items 1 and 2 are refused by design. Owner's phone is needed for any of it.
