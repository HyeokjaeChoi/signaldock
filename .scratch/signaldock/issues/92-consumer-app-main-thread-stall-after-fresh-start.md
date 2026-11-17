# 92. Consumer app main-thread stall of 2.5 to 2.7 s after a fresh start (E2E cases 3 and 5)

Status: needs-triage
Type: task
Severity: Minor

In the instrumented nightly run 43879822305 (https://github.com/HyeokjaeChoi/signaldock/actions/runs/43879822305), the consumer app (R8 release) had a main-thread stall of 2.5 to 2.7 s (`EGL_emulation app_time_stats max=2512..2682ms`) right after a fresh start in E2E cases 3 and 5. At that time the script ran `uiautomator dump` and taps. The input was delivered in one burst after the stall.

The source was not examined. It can be the SDK `initialize` or `track` on the main thread, the R8 cold start, or the uiautomator accessibility service.

The E2E fix (ADR-0010 amendment 2026-11-16) removes the dependency of the harness on the stall, so the stall is no longer visible there.

Next step: measure with a trace, or with the ADR-0020 host-cost numbers, whether the SDK takes part in the stall. If it does, fix it in the SDK; the main-thread `track()` p99 budget is ADR-0049 A4.
