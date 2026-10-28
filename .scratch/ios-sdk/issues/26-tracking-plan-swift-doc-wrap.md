# 26. Tracking plan Swift output: doc comment wrap for one long token

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the local review of iOS ticket 11, recorded under AGENTS.md rule 7.

`swiftDoc` in `tracking-plan/generate.mjs` wraps an event description at 100 columns. A single word longer than 116 characters still makes a doc line over the 120 columns of `swift format`, so `scripts/verify-ios.sh lint` fails on the generated file. No plan event has such a word now.

1. Decide: reject such a description in `validatePlan`, or let `swiftDoc` split the token.
2. Add a row to the invalid-plan table (or a wrap test) in `tracking-plan/generate.test.mjs`.

Verification:
- `node --test tracking-plan/generate.test.mjs` and `IOS_WERROR=1 scripts/verify-ios.sh lint` pass.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
