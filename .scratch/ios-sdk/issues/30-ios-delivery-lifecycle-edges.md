# 30. iOS delivery: lifecycle edge cases

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor findings from the Claude review of PR #258 on 4fc49ae, recorded under AGENTS.md rule 7. Neither loses data.

1. `DeliveryTrigger.runDrains` runs the "once more" drain without a check of the active flag. When a background task expires, a foreground-trigger drain that waits for the `Sender` lock takes the lock and starts a new request after the task ended (`BackgroundDrain.swift:71`, `DeliveryTrigger.swift:184`). Result: a spurious failure and backoff, or a duplicate resend. Fix: check the active flag before the extra drain, and before a request starts after the lock wait.
2. `DeliveryCoordinator.swift` reads `applicationState` and registers the `LifecycleObserver` in two separate `MainActor.run` hops, so a lifecycle notification between them is lost. Fix: do both in one hop.

Each fix has a test, with the deterministic transport fake and the injected clock (no sleeps).

Verification:
- `IOS_WERROR=1 scripts/verify-ios.sh lint` and `scripts/verify-ios.sh tsan` pass.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
