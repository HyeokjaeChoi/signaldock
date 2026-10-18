# Security policy

SignalDock is an open-source project. The stack is for local use only. Read [Scope](#scope) before you report.

## Supported versions

| Version | Security fixes |
| --- | --- |
| 0.2.x | Best effort |
| 0.1.x | None (superseded by 0.2.x) |
| Earlier than 0.1.0 | None (no release exists) |

The project is before 1.0 and has one maintainer. There is no fixed response time and no SLA. See [docs/versioning.md](docs/versioning.md) for the support window.

## Verify a release

Each GitHub Release has a Maven ZIP and a `.sha256` file. Releases after ADR-0039 also have a signed build provenance attestation. To check that this repository's release workflow built a ZIP, run:

```sh
gh attestation verify signaldock-sdk-android-X.Y.Z-maven.zip --repo HyeokjaeChoi/signaldock
```

Use GitHub CLI 2.102.0 or newer (older versions have attestation check flaws, for example GHSA-wjmr-j3rp-mh2g). `v0.2.0` and earlier have no attestation. See [docs/releases/README.md](docs/releases/README.md#provenance-attestation).

## Report a vulnerability

Do not open a public issue or pull request for a vulnerability.

Use GitHub private vulnerability reporting for this repository:

1. Open the [Security tab](https://github.com/HyeokjaeChoi/signaldock/security).
2. Select **Report a vulnerability**.
3. Describe the problem, the affected component, the steps to reproduce it, and the version or commit.

If the **Report a vulnerability** button is not shown, open a public issue that says only that you need a private channel. Do not put details in it.

## Scope

In scope:

- The Android SDK (`sdk/sdk-android`): for example, events or the collection key exposed to other apps, or input that crashes the host app.
- The server (`server/`): for example, authentication bypass between the collection key and the query key, SQL injection, or an ACK sent before commit.
- The Dashboard (`dashboard/`): for example, script injection through stored event data, or exposure of the query key to the browser.

Out of scope (known non-goals):

- Operation on the public internet. The stack is local only. It binds to `127.0.0.1`, uses example-grade secrets, and has no TLS ([README](README.md#limitations)).
- Extraction of the collection key from an app. The collection key is not a secret that cannot be extracted from the SDK. It checks access for local evaluation and does not replace user authentication (spec section 5, [spec](.scratch/signaldock/spec.md#5-server-receipt-and-duplicate-handling)). It is write-only: it cannot read events (spec Q128).
- A native-web (WebView JavaScript) bridge and other bridges (spec Q150). They do not exist.
- Findings that need physical access to a device with the app data, or that need an attacker who already controls the local machine.
