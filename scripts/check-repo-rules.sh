#!/bin/bash
# Fast static checks for review findings that recurred on PR #9.
# Runs from .githooks/pre-commit and .githooks/pre-push. Reads the git index,
# so it checks what will be committed, not the working tree.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fail=0

# Scripts that CI or verification invokes directly must be executable (100755).
# Regressed twice: codegen.sh (review round 2) and verify-contract.sh (8ae7516).
while read -r mode _ _ path; do
  if [ "$mode" != "100755" ]; then
    echo "error: $path is mode $mode; run: git update-index --chmod=+x $path" >&2
    fail=1
  fi
done < <(git ls-files -s -- '*.sh' 'gradlew' '*/gradlew' '.githooks/*')

# Unresolved issue/PR placeholders such as "issue #" with no number.
if git grep --cached -nE '(issue|PR) #([^0-9]|$)' -- '*.md' >&2; then
  echo "error: unresolved issue/PR placeholder above; write the number or remove it" >&2
  fail=1
fi

exit "$fail"
