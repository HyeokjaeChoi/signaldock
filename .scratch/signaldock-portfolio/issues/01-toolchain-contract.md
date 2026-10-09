# 01. Toolchain and OpenAPI round trip

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started; prefer a low-cost model, with no restriction to a specific model

## Scope and completion criteria

Pin compatible versions, generate and compile Kotlin/TypeScript scalar and ACK DTOs, verify JSON round-trip fixtures, and check independent Gradle paths.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

Toolchain research specified model=gpt-6.1-sol, but the runtime blocked it with `The 'gpt-6.1-sol' model is not supported when using Codex with a ChatGPT account.` A more expensive model was not substituted. The implementation worker was also asked to stop and report changes. No claim is made that compatible versions were researched or that a build succeeded.

The implementation worker reported that it only inspected documentation and the environment. It did not create or modify product files or run build/codegen/test. That worker did not receive the same model error directly. The research worker's error does not establish that all gpt-6.1-sol execution is unavailable. Both workers were terminated. The model path must be checked on the next run.

2026-10-09 user correction: The model name was an example, not a requirement to use that specific model. Prefer a low-cost model and do not treat the failure of that one model as a blocker for all implementation. Product code and build/codegen/test remain not started.
