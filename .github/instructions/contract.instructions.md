---
applyTo: "openapi/**,fixtures/**,generated/**,contract-check/**,dashboard/src/contract-check.ts,scripts/codegen.sh"
---

# Contract and codegen review rules

- `openapi/contract-v1.yaml` is the source of truth. Generated code is committed but never hand-edited: review the contract and the templates for semantic changes, not generated diffs. The drift check in `scripts/verify-contract.sh` enforces this.
- Request schemas use `additionalProperties: false`; free-form keys inside `properties` are exempt. (PR #9)
- The `SignalDock-Contract-Version` header is required. A missing header rejects the whole request with HTTP 400 and stores nothing. Never default a missing header to contract 1.
- Compatibility checks (oasdiff) must compare against a checked-in prior baseline, never the contract against itself. (PR #9)
- Contract samples must execute assertions, not just typecheck. A sample that compiles but never runs cannot catch conversion regressions. (PR #9)
