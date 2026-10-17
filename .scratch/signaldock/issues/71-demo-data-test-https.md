# 71. Demo data unit test fails the https rule

Status: claimed
Type: task
Blocked by: None
Execution: PR pending (branch fix/demo-data-test-https)

## Scope and completion criteria

ADR-0035 rejects an `http` endpoint unless `allowInsecureEndpoint = true`. The test `sdk/demo/core/data/src/test/kotlin/local/signaldock/demo/core/data/SdkSignalDockClientTest.kt` used `http://localhost:9` without the flag, so `:demo:core:data:testDebugUnitTest` failed on main. CI did not run this module.

1. Set `allowInsecureEndpoint = true` in the test config (ADR-0035: unit test configs set `true` for loopback hosts). No SDK or product code change. No ADR.
2. Add `:demo:core:data:testDebugUnitTest` to the unit-test command in `.github/workflows/repo-rules.yml`.
3. Check the other unit-test tasks of the `:sdk` build. `demo:app`, `demo:core:ui`, and `measure-app` have no test sources, so they are not added.

Verification:
- `sdk/gradlew -p sdk :demo:core:data:testDebugUnitTest --no-daemon` fails on main (`AssertionError`, SdkSignalDockClientTest.kt:29) and passes with the fix.
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :demo:feature:shop:testDebugUnitTest :demo:feature:diagnostics:testDebugUnitTest :demo:core:data:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL.

## Comments

- 2026-10-18 baseline (main, before the fix): `sdk/gradlew -p sdk :demo:core:data:testDebugUnitTest --no-daemon`: BUILD FAILED, 1 test failed.
- 2026-10-18 verification (branch, after the fix): the CI test command above: BUILD SUCCESSFUL. Test counts from the JUnit XML reports: data 1 (0 failures), shop 8 (0), diagnostics 5 (0), sdk-android 167 (0).
