# 17. Demo shop: keep lastTrack tied to the latest action

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Done (merged in PR #33, 2026-10-12)

## Scope and completion criteria

From the CodeRabbit Minor finding on PR #33 (issue 16). In `ShopViewModel.track`, two `client.track` calls can finish out of order. An older result can then overwrite `lastTrack` for a newer action, and the screen names the wrong action as the latest result. Give each attempt a sequence number and update `lastTrack` only for the newest attempt.

Done when a `ShopViewModelTest` case with out-of-order results passes.

## Comments

2026-10-12: Recorded from the PR #33 review (Minor, merge not held, AGENTS.md "Review workflow" 5).

2026-10-12: Done. `ShopViewModel.track` now takes an increasing private sequence number per attempt and updates `lastTrack` only if the attempt is still the newest. Added `ShopViewModelTest.an_older_result_that_finishes_late_does_not_overwrite_lastTrack` (a `CompletableDeferred`-gated fake completes the second action first, then the first; asserts `lastTrack` names `add_to_cart`). Existing tests unchanged.
Verification: `JAVA_HOME=$PWD/.tools/jdk-21.0.12.1+1 ANDROID_HOME=$HOME/Library/Android/sdk sdk/gradlew -p sdk :demo:feature:shop:testDebugUnitTest :demo:app:assembleDebug --no-daemon` -> BUILD SUCCESSFUL in 9s (138 actionable tasks: 9 executed, 129 up-to-date).

2026-10-12: Record reconciled. Merged into main in PR #33 on 2026-10-12. Earlier branch and push notes above describe the state before that merge.
