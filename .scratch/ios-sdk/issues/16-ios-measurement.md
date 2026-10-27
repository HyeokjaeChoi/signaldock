# 16. iOS size and host-cost measurement

Status: ready-for-agent
Type: task
Blocked by: 14

## Scope and completion criteria

Spec D15. ADR-0020 (method), ADR-0014.

1. Size: consumer app Release arm64, unsigned, with and without the SDK; report app size and the SDK Mach-O contribution.
2. Host cost on the Simulator: `initialize()` and `track()` main-thread time (p50, p95), cold start, memory after idle; repetitions as in ADR-0020.
3. Results with environment records in `perf/sdk-results/ios-*/`; a short ADR or ADR-0047 amendment with the method.

Verification:
- The script command and the result files.
