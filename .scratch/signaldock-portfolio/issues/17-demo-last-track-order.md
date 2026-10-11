# 17. Demo shop: keep lastTrack tied to the latest action

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

From the CodeRabbit Minor finding on PR #33 (issue 16). In `ShopViewModel.track`, two `client.track` calls can finish out of order. An older result can then overwrite `lastTrack` for a newer action, and the screen names the wrong action as the latest result. Give each attempt a sequence number and update `lastTrack` only for the newest attempt.

Done when a `ShopViewModelTest` case with out-of-order results passes.

## Comments

2026-10-12: Recorded from the PR #33 review (Minor, merge not held, AGENTS.md "Review workflow" 5).
