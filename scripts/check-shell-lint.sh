#!/bin/bash
# Lint: shellcheck (warning and above) on every tracked *.sh and .githooks/* file, and
# actionlint on .github/workflows (ADR-0049 D1, D2). Uses only the pinned tools in
# .tools/ (scripts/setup-toolchain.sh lint), never a copy on PATH, so a local run and
# CI report the same findings. Runs from .githooks/pre-commit and the CI `check` job.
# It reads the working tree: shellcheck must follow `source` paths on disk.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
SHELLCHECK="$PWD/.tools/shellcheck"
ACTIONLINT="$PWD/.tools/actionlint"

for tool in "$SHELLCHECK" "$ACTIONLINT"; do
  if [ ! -x "$tool" ]; then
    echo "error: $tool is missing; run: scripts/setup-toolchain.sh lint" >&2
    exit 1
  fi
done
"$SHELLCHECK" --version | grep -qx 'version: 0\.11\.0' || { echo "error: shellcheck is not 0.11.0; run: scripts/setup-toolchain.sh lint" >&2; exit 1; }
"$ACTIONLINT" --version | head -1 | grep -qx '1\.7\.12' || { echo "error: actionlint is not 1.7.12; run: scripts/setup-toolchain.sh lint" >&2; exit 1; }

files=()
# A tracked file that is deleted in the working tree (not yet staged) is skipped, not an error.
while IFS= read -r -d '' f; do if [ -e "$f" ]; then files+=("$f"); fi; done < <(git ls-files -z -- '*.sh' '.githooks/*')
[ "${#files[@]}" -gt 0 ] || { echo "error: no shell files found; the pathspec is wrong" >&2; exit 1; }

fail=0
# -x follows `source` into files that are not on the command line.
"$SHELLCHECK" -x --severity=warning "${files[@]}" || fail=1
# actionlint runs the same pinned shellcheck on `run:` blocks. -pyflakes= turns off a
# tool that is not pinned.
"$ACTIONLINT" -shellcheck="$SHELLCHECK" -pyflakes= || fail=1

[ "$fail" = 0 ] && echo "shellcheck: ${#files[@]} files, actionlint: .github/workflows: no findings"
exit "$fail"
