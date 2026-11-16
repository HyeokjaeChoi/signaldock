# 14. Semver check follow-ups (Minor findings of PR 290)

Status: claimed
Type: task

Open Minor findings of ticket 09 (PR 290, `scripts/check-api-semver.sh`):

1. Tag lookup uses only the tags of `origin`; in pre-push from a fork with old tags, the check can compare against an old release. CI is not affected. Option: fail with a message when `origin` is not the canonical repository.
2. The compared tag is the highest release tag, not the nearest ancestor of the branch (backport branches).
3. `ls-remote` failure falls back to local tags without a warning.
4. A MINOR bump before 1.0 passes a removal without the deprecation period of `docs/versioning.md`; this stays a review item.
5. A repository check that tests creating a temporary git repository unset `GIT_DIR` (hook environment).
6. Compiler printing: after a pinned Xcode bump and `scripts/verify-ios.sh api-update`, a change in how the compiler prints `SignalDock.swiftinterface` shows as a changed line, and I3 then asks for a MINOR bump although the API did not change. Name this limit in the ADR-0049 amendment, or compare a normalized form.

## Comments

Handled in branch `claude/semver-followups-14-64867e`:

- Item 1: `scripts/check-api-semver.sh` fails when `SEMVER_REMOTE` (default `origin`) is not the canonical repository; the message says to set `SEMVER_REMOTE`.
- Item 2: the compared tag is the highest release tag that is an ancestor of `HEAD`; a shallow checkout is unshallowed first.
- Item 3: an `ls-remote` failure prints a warning before it uses local tags.
- Item 5: `scripts/test_check_coverage.py` and `scripts/test_check_baselines.py` drop the `GIT_*` variables; `scripts/check-repo-rules.sh` checks that every test that creates a git repository mentions `GIT_DIR`.
- Item 6: the limit is named in the ADR-0049 amendment of ticket 09, not normalized: only the compiler that made the old interface can print it again, and a text normalizer would hide real changes or miss printing changes.

Item 4 stays a review item (`docs/versioning.md`, deprecation period); code does not check it.
