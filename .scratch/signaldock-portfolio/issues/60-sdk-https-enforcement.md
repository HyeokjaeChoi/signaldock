# 60. SDK rejects a non-HTTPS endpoint unless the host opts out

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/sdk-https-enforcement`, ADR-0035 (spec Q163)

## Scope and completion criteria

Spec Q163: the SDK rejects an endpoint whose scheme is not `https` at init, with an error that names the option, unless the host sets `allowInsecureEndpoint = true`. Default is false.

1. `initialize` returns `InvalidConfiguration` for `http` (any case) without the flag, and for other schemes and malformed URIs. `http` with the flag is accepted. Unit tests cover https, http, http with flag, and malformed.
2. Public API: `SignalDockConfig.allowInsecureEndpoint`. Update the API dump, both integration guides, `CHANGELOG.md`, the threat model (keep the "no pinning" gap), and the design index.
3. The demo app and the consumer app set the flag when their endpoint starts with `http://`. Device test and measure configs set it.

Verification:
- `sdk/gradlew -p sdk :sdk-android:test`; `:sdk-android:apiCheck`; demo app build; `scripts/verify-consumer.sh`; the pre-push hook (`scripts/verify-contract.sh`); `scripts/check-repo-rules.sh`.
