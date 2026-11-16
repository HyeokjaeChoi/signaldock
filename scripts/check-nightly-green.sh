#!/bin/bash
# Release gate D4 (ADR-0049 decisions 1 and 8): the last `nightly.yml` run on GITHUB_SHA must be `success`.
# Runs in the `build` job of release.yml and release-ios.yml, which has `actions: read`. Needs GH_TOKEN, GITHUB_SHA, GITHUB_REPOSITORY.
# No run, a run that is not finished, or any other conclusion fails. No retry (decision 5): start a run with
#   gh workflow run nightly.yml --ref <tag>     (runs on the tagged commit), wait for it, then re-run the release.
# Test: scripts/check-nightly-green.test.sh
set -euo pipefail

: "${GITHUB_SHA:?}" "${GITHUB_REPOSITORY:?}"
# The newest run is first. A run that is not done has no conclusion, so its status is shown.
if ! result="$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/nightly.yml/runs?head_sha=$GITHUB_SHA&per_page=1" \
  --jq 'if (.workflow_runs | length) == 0 then "none" else .workflow_runs[0] | "\(.conclusion // .status) \(.html_url)" end' 2>&1)"; then
  echo "error: could not read the nightly runs of $GITHUB_SHA: $result" >&2
  echo "The job needs the permission 'actions: read' (and a GH_TOKEN) to list workflow runs; see the job's permissions." >&2
  exit 1
fi
case "$result" in
  "success "*) echo "nightly is green on $GITHUB_SHA: ${result#success }" ;;
  none) echo "error: no nightly.yml run on $GITHUB_SHA. Run 'gh workflow run nightly.yml --ref <tag>', wait for success, then release (ADR-0049 D4)." >&2; exit 1 ;;
  *) echo "error: the last nightly.yml run on $GITHUB_SHA is not success: $result (ADR-0049 D4)" >&2; exit 1 ;;
esac
