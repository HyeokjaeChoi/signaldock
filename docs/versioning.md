# Versioning and deprecation policy

This policy covers the Android SDK (`local.signaldock:sdk-android`). The server, the Dashboard, and the contract have their own rules below. The policy sets what a version number promises. It adds no product behavior.

## SDK version

The SDK follows [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

| Part | Raise it when |
| --- | --- |
| MAJOR | A breaking change (see below) |
| MINOR | A compatible addition or a deprecation |
| PATCH | A compatible fix |

The SDK is before 1.0 (`0.x`). Until 1.0, a MINOR release can contain a breaking change. The change must be listed under "Changed" or "Removed" in [CHANGELOG.md](../CHANGELOG.md). The deprecation rule below still applies to `0.x`.

## What is a breaking change

A change is breaking if an app that builds and works with the old version can fail to build or behave differently with the new one. These cases count:

1. **Public API.** Any difference in the committed dump `sdk/sdk-android/api/sdk-android.api` that removes or changes a declaration. `sdk/gradlew -p sdk :sdk-android:apiCheck` fails on every difference. A pure addition is compatible. A removal or signature change is breaking ([ADR-0019](adr/0019-sdk-public-api-check.md)).
2. **Documented behavior.** A change to the public behavior in spec section 3 (`track()` results, validation rules, cancellation, collection versus receipt success), for example another result type for the same input ([spec](../.scratch/signaldock/spec.md#3-sdk-public-behavior)).
3. **Persisted data format.** A change to the Room schema or to the stored queue that the previous version cannot read, or that an upgrade would lose. A schema change needs a migration; the exported schema is in `sdk/sdk-android/schemas/` ([ADR-0003](adr/0003-sdk-storage-decisions.md)).
4. **Requirements.** A higher `minSdk`, `compileSdk`, or minimum Kotlin or AGP version. The current values are in the [README](../README.md#supported-versions).

A fix of behavior that did not match the documented behavior is a PATCH, not a breaking change.

## Deprecation

Modeled on the Sentry SDK rule for [deprecating an API](https://develop.sentry.dev/sdk/getting-started/playbooks/sdk-lifecycle/deprecating-an-api/):

- Mark the API `@Deprecated` with a message and a replacement. List it in the changelog under "Deprecated".
- A deprecated API stays functional for at least one full minor release.
- A deprecated API is removed only in the next major release. In `0.x`, removal can happen only after one full MINOR release has passed with the API deprecated and working: an API deprecated in 0.2 can be removed in 0.4 at the earliest, listed under "Removed".

This section is a policy, not a record. No API is deprecated today.

## Contract version and SDK version

The contract version and the SDK version are separate numbers ([ADR-0012](adr/0012-contract-and-db-change-checks.md), [integration guide](integration-guide.md#9-versions-and-the-contract-version)).

- The contract version (now `1`) describes the HTTP and JSON interface. The SDK sends it in the header `SignalDock-Contract-Version`.
- A compatible contract change (for example an optional field) keeps the contract version. `oasdiff breaking` against `fixtures/contract/contract-v1.baseline.yaml` must report nothing.
- An incompatible contract change raises the contract version and needs a new ADR.
- Raising the contract version does not by itself require a new SDK MAJOR version, and a new SDK MAJOR version does not require a new contract version.
- The SDK keeps sending events under the contract they were collected with. A server that does not support a contract version rejects the request, and the SDK keeps its queue.

## Support window

- Only the latest `0.x` release gets fixes, on a best-effort basis. There is no SLA and no promise of a date.
- There are no long-term support branches. The project has one maintainer and is an open-source project.
- The SDK is distributed as a Maven repository ZIP on GitHub Releases, not on Maven Central.
- After 1.0, this section will be revised. Until then, do not rely on a support period.

## For contributors

When you change the public API: run `sdk/gradlew -p sdk :sdk-android:apiDump`, then update the integration guide (both languages) and `CHANGELOG.md` in the same pull request. For a deprecation or removal, follow the rule above.
