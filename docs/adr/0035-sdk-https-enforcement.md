---
status: accepted
---

# SDK HTTPS enforcement (issue 60, spec Q163)

The user decided (Q163, 2026-10-16 UTC) that the SDK requires an `https` endpoint unless the host opts out.

## Context

- The SDK sends the collection key as a Bearer token in every request.
- Android blocks cleartext by default from targetSdk 28. A host that permits cleartext (network security config) would make the SDK send the key in plain text. The threat model NETWORK row said HTTPS is not enforced.
- The demo app and the consumer app use `http://10.0.2.2:8080` (the emulator host). The consumer app uses it even in release (ADR-0009 decision 5).

## Decision

1. `validateConfig` (`internal/validation/Validation.kt`) rejects an endpoint whose scheme is not `https`. The scheme check is case-insensitive. A scheme other than `http` or `https`, a missing host, and a malformed URI are rejected as before.
2. `SignalDockConfig.allowInsecureEndpoint: Boolean = false`. With `true`, an `http` endpoint is accepted. It does not allow other schemes.
3. The failure is `InitResult.Failure(InitError.InvalidConfiguration(message))`. This is the existing failure style. No new error type. The message names the option and never contains the key. Config equality (the `ConfigurationConflict` check) includes the flag, because it is a constructor property.
4. Demo app and consumer app: `allowInsecureEndpoint = BuildConfig.SIGNALDOCK_ENDPOINT.startsWith("http://", ignoreCase = true)`. An `https` endpoint set by a Gradle property keeps the strict rule. `scripts/verify-consumer.sh` and the e2e scripts need no change: they pass the endpoint through `-Psignaldock.endpoint`, which reaches `BuildConfig`. The device test, measure, and unit test configs set `true` (loopback and emulator hosts).

## Rejected

A body property (`var`, as in ADR-0030). It keeps the JVM signatures, but `copy` drops it, it is mutable, and it needs a manual compare in the conflict check. The constructor parameter changes the JVM constructor and `copy` signatures only. Callers compile without change. `docs/versioning.md` allows this before 1.0, and the CHANGELOG says so.

## Limits

- Gap: no certificate pinning. HTTPS only protects against a network attacker who has no valid certificate.
- The host can still set the flag in a release build. The flag is a clear, searchable opt-out, not a lock.
- The check is at init only. The SDK does not follow redirects, so a redirect to `http` does not happen.
- Gap: the instrumented (device) tests were changed to set the flag but were not run on a device in this change.
