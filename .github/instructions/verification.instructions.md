---
applyTo: "scripts/**,.githooks/**,dashboard/package*.json,TOOLCHAIN.md,gradle/**,**/*.gradle.kts"
---

# Toolchain and verification review rules

Executable file modes and issue placeholders are enforced by `scripts/check-repo-rules.sh`. Do not repeat those findings by hand.

- Toolchain absence: verification scripts fail with an explicit setup message when a pinned tool (JDK, generator) is missing. Never silently fall back to whatever is on PATH. Silent fallback makes results environment-dependent and defeats the pinned toolchain. (PR #9)
- Lockfiles are committed. Verification runs `npm ci` (not a bare `npm run`) so the pinned TypeScript version is actually used. (PR #9)
- Never label a step "verified" without the actual run. If an environment could not run a tool, state that instead of claiming verification. (PR #9)
