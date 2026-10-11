#!/bin/bash
# Promote CodeRabbit "Learnings added" from one PR into docs/review/*.md.
# Usage: scripts/learnings-sync.sh <pr-number>   (needs gh, jq, yq)
# Env: DRY_RUN=1 prints the plan and writes nothing.
#      IGNORE_RECORDED=1 skips the "PR already changed docs/review/" exit (tests only).
# Stdout is a Markdown summary for the PR body; notes go to stderr.
set -euo pipefail

pr=${1:?usage: learnings-sync.sh <pr-number>}
cd "$(git rev-parse --show-toplevel)"
repo=${GH_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}
heading='### Promoted from CodeRabbit learnings'

files=$(gh api --paginate "repos/$repo/pulls/$pr/files" --jq '.[].filename')
if [ -z "${IGNORE_RECORDED:-}" ] && grep -q '^docs/review/' <<<"$files"; then
  echo "rules already recorded in this PR" >&2
  exit 0
fi

# Glob rules come from .coderabbit.yaml so they are written once.
patterns=$(yq -r '.knowledge_base.code_guidelines.filePatterns[] | .applyTo + "\t" + .files' .coderabbit.yaml)

target_for() {
  local path=${1%:*} glob file
  while IFS=$'\t' read -r glob file; do
    [ "$glob" = '**' ] && continue
    # ponytail: bash glob, "*" also crosses "/"; add a regex if a pattern needs "*" to stop at "/".
    # shellcheck disable=SC2053
    [[ $path == $glob ]] && { echo "$file"; return; }
  done <<<"$patterns"
  echo docs/review/repo.md
}

# One TSV line per learning: url, file, text (wrapped lines joined by spaces).
learnings() {
  { gh api --paginate "repos/$repo/issues/$pr/comments" --jq '.[]' &&
    gh api --paginate "repos/$repo/pulls/$pr/comments" --jq '.[]'; } |
    jq -c 'select(.user.login == "coderabbitai[bot]") | {url: .html_url, body}' |
    while IFS= read -r c; do
      url=$(jq -r .url <<<"$c")
      jq -r .body <<<"$c" | awk -v url="$url" '
        function flush() { if (txt != "") print url "\t" file "\t" txt; txt = ""; file = ""; on = 0 }
        /^```/ { flush(); next }
        /^Learnt from:/ { flush(); on = 1; next }
        !on { next }
        /^File: / { file = substr($0, 7); next }
        /^Learning: / { txt = substr($0, 11); next }
        txt != "" && !/^(Repo|Timestamp): / { txt = txt " " $0 }
        END { flush() }'
    done
}

changed=0
while IFS=$'\t' read -r url path text; do
  [ -n "$text" ] || continue
  target=$(target_for "$path")
  if [ -f "$target" ] && grep -qF -- "$text" "$target"; then
    echo "skip (already in $target): $url" >&2
    continue
  fi
  line="- $text (PR #$pr)"
  echo "- \`$target\`: $url"
  if [ -n "${DRY_RUN:-}" ]; then
    echo "  would append: $line"
    continue
  fi
  [ -n "$(tail -c1 "$target")" ] && echo >>"$target"
  # ponytail: assumes the heading is the last section of the file.
  grep -qxF "$heading" "$target" || printf '\n%s\n' "$heading" >>"$target"
  echo "$line" >>"$target"
  changed=1
done < <(learnings)

[ "$changed" = 1 ] || [ -n "${DRY_RUN:-}" ] || echo "no new learnings to promote" >&2
