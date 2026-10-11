# 16. Android demo app (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

Compose demo app in `sdk/` as ADR-0005, section "Demo app: feature modules and unidirectional data flow", defines: `:demo:app`, `:demo:feature:shop`, `:demo:feature:diagnostics`, `:demo:core:ui`, `:demo:core:data`. compileSdk 37 (ADR-0006, decision 1). Spec section 6 "Demo and diagnostics": one `track` per user action, no recollection on recomposition, text that separates device collection results from server receipt results, diagnostics on screen entry and manual refresh.

Done when the app builds, its ViewModel tests pass, and it runs on an Android 17 (API 37.2) emulator against a local server, with commands, a screenshot, and results recorded here.

## Comments

2026-10-12: Split from issue 05.
