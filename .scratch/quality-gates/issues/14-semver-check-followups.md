# 14. Semver check follow-ups (Minor findings of PR 290)

Status: ready-for-agent
Type: task

Open Minor findings of ticket 09 (PR 290, `scripts/check-api-semver.sh`):

1. Tag lookup uses only the tags of `origin`; in pre-push from a fork with old tags, the check can compare against an old release. CI is not affected. Option: fail with a message when `origin` is not the canonical repository.
2. The compared tag is the highest release tag, not the nearest ancestor of the branch (backport branches).
3. `ls-remote` failure falls back to local tags without a warning.
4. A MINOR bump before 1.0 passes a removal without the deprecation period of `docs/versioning.md`; this stays a review item.
5. A repository check that tests creating a temporary git repository unset `GIT_DIR` (hook environment).
6. Compiler printing: after a pinned Xcode bump and `scripts/verify-ios.sh api-update`, a change in how the compiler prints `SignalDock.swiftinterface` shows as a changed line, and I3 then asks for a MINOR bump although the API did not change. Name this limit in the ADR-0049 amendment, or compare a normalized form.
