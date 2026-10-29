# 16. iOS size and host-cost measurement

Status: resolved
Execution: Done (PR #266, 2026-10-29 UTC)
Type: task
Blocked by: 14

## Scope and completion criteria

Spec D15. ADR-0020 (method), ADR-0014.

1. Size: consumer app Release arm64, unsigned, with and without the SDK; report app size and the SDK Mach-O contribution.
2. Host cost on the Simulator: `initialize()` and `track()` main-thread time (p50, p95), cold start, memory after idle; repetitions as in ADR-0020.
3. Results with environment records in `perf/sdk-results/ios-*/`; a short ADR or ADR-0047 amendment with the method.

Verification:
- The script command and the result files.

## Verification (2026-10-29)

- `scripts/measure-ios-sdk.sh` (no argument: fresh ZIP of HEAD, own Simulator, 20 startup runs, 10 burst runs of 500 `track()`): exit 0. Result: `perf/sdk-results/ios-0.1.0-20261029/` (`summary.md`, `size.csv`, `sdk-sections.csv`, `link-objects.csv`, `dependencies.txt`, `startup.csv`, `init.csv`, `track-samples.csv`, `memory-burst.csv`, `meta.json`). Environment: Xcode 27.1 (27A9275), Swift 6.4, macOS 26.6.2, Apple M2 Pro, iPhone 18 Pro iOS 27.0 Simulator, revision 91aece9, clean tree. The host was busy (1-minute load average up to 260 on 12 CPUs), so only medians are used.
- Method and numbers: ADR-0047 "Amendment (2026-10-29, ticket 16, measurement method)".
- Also run: `scripts/check-repo-rules.sh`, `IOS_WERROR=1 scripts/verify-consumer-ios.sh`.
- Not run: a physical iPhone; CI (the script is local only); a rerun on a quiet host.
