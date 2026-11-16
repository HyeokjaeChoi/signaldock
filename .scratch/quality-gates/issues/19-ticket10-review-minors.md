# 19. Ticket 10 review findings left open (Minor and lower)

Status: ready-for-agent
Type: task

Found by the review of PR 299 (ticket 10, ADR-0049 Q3, D4). None blocks a gate.

1. (Minor) `scripts/verify-ios.sh` `all` runs `run_coverage` with no compiler-version skip. `scripts/verify-contract.sh` step 8 (pre-push) calls `verify-ios.sh all` on any Mac with full Xcode. `run_api` skips outside CI when the local Swift differs from `sdk-ios/api/compiler-version`. A local Xcode can measure more than 0.5 point away from the CI value of 94.12. Then pre-push fails, and if you commit the local value, CI `ios-test` fails. Fix: give `run_coverage` the same compiler-version skip as `run_api` when `CI` is not `true` (ADR-0049 ticket 10 amendment: "CI is the source of truth").
