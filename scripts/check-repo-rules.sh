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

# Issue status: a triage name, or claimed/resolved (issue-tracker.md); Done work must be resolved (PR #56 review).
for f in $(git ls-files -- '.scratch/*/issues/*.md'); do
  status="$(git show ":$f" | sed -n 's/^Status: //p' | head -1)"
  case "$status" in needs-triage|needs-info|ready-for-agent|ready-for-human|wontfix|claimed|resolved) ;;
    *) echo "error: $f has unknown Status '$status'" >&2; fail=1 ;; esac
  if git show ":$f" | grep -q '^Execution: Done' && [ "$status" != resolved ]; then
    echo "error: $f says Execution: Done but Status is '$status'; set Status: resolved" >&2; fail=1
  fi
  if git show ":$f" | grep -q '^Execution: .*PR pending' && [ "$status" != claimed ]; then
    echo "error: $f has a pending PR but Status is '$status'; set Status: claimed" >&2; fail=1
  fi
  if git show ":$f" | grep -q '^Execution: Done' && ! git show ":$f" | grep -q '^Execution: Done (.*PR #[0-9]'; then
    echo "error: $f says Execution: Done but names no merged PR (issue-tracker.md)" >&2; fail=1
  fi
done

# Dashboard, perf report, and web-shared render free text from key holders. React escaping is the only
# protection, so raw HTML sinks are not allowed there (issue 46, threat model). Tests are exempt.
if git grep --cached -nE 'dangerouslySetInnerHTML|innerHTML|outerHTML|insertAdjacentHTML' -- 'dashboard/src' 'perf/report/src' 'web-shared' ':!*.test.*' >&2; then
  echo "error: raw HTML sink above; render text through React instead (issue 46)" >&2
  fail=1
fi

# README.md and README.ko.md must stay in step (issue 26): same heading count per
# level, same set of relative link targets that exist in the index (in-page #anchors ignored; Korean
# headings give other anchors), and each links to the other. Fenced code is skipped.
if ! python3 - <<'PY'
import re, subprocess, sys

MUTUAL = {"README.md", "README.ko.md"}  # each file links to the other by design

def read(path):
    return subprocess.run(["git", "show", ":" + path], capture_output=True, text=True, check=True).stdout

def scan(text):
    levels, links, fenced = {}, set(), False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        m = re.match(r"(#{1,6})\s", line)
        if m:
            levels[len(m.group(1))] = levels.get(len(m.group(1)), 0) + 1
        for t in re.findall(r"\]\(([^)\s]+)\)", line):
            if not t.startswith(("#", "http://", "https://", "mailto:")):
                links.add(t)
    return levels, links

try:
    en, ko = scan(read("README.md")), scan(read("README.ko.md"))
except subprocess.CalledProcessError:
    sys.exit("error: README.md or README.ko.md is not in the git index")
bad = []
if en[0] != ko[0]:
    bad.append(f"heading counts per level differ: README.md {sorted(en[0].items())} vs README.ko.md {sorted(ko[0].items())}")
for name, a, b in (("README.md", en[1], ko[1]), ("README.ko.md", ko[1], en[1])):
    diff = (a - b) - MUTUAL
    if diff:
        bad.append(f"link targets only in {name}: {sorted(diff)}")
files = set(subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines())
dirs = {f.rsplit("/", i)[0] for f in files for i in range(1, f.count("/") + 1)}
for name, (_, links) in (("README.md", en), ("README.ko.md", ko)):
    for t in sorted(links):
        path = t.split("#", 1)[0].rstrip("/")
        if path and path not in files and path not in dirs:
            bad.append(f"{name} links to {t}, which is not in the git index")
if "README.ko.md" not in en[1]:
    bad.append("README.md has no link to README.ko.md")
if "README.md" not in ko[1]:
    bad.append("README.ko.md has no link to README.md")
for b in bad:
    print("error: " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PY
then
  fail=1
fi

# docs/design-index.md is the one source of area guidance (Q138; no per-area .claude/rules files).
# Every top-level directory is named in a `## ` heading, and every path a heading names exists.
if ! python3 - <<'PY'
import re, subprocess, sys

EXEMPT = {".claude", ".scratch", "docs"}  # agent settings, issue tracker, and the design sources themselves
files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()
index = subprocess.run(["git", "show", ":docs/design-index.md"], capture_output=True, text=True, check=True).stdout
named = [p.rstrip("/") for line in index.splitlines() if line.startswith("## ") for p in re.findall(r"`([^`]+/)`", line)]
tops = {f.split("/")[0] for f in files if "/" in f} - EXEMPT
bad = [f"top-level directory {d}/ has no section in docs/design-index.md" for d in sorted(tops)
       if not any(n == d or n.startswith(d + "/") for n in named)]
bad += [f"docs/design-index.md names {n}/, which has no tracked file" for n in named
        if not any(f.startswith(n + "/") for f in files)]
for b in bad:
    print("error: " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PY
then
  fail=1
fi

# A released Room schema file is frozen (ADR-0025). A schema change needs a new version, a migration, and a new N.json.
if git rev-parse --verify -q origin/main >/dev/null; then
  for f in $(git ls-tree -r --name-only origin/main -- sdk/sdk-android/schemas); do
    if ! git diff --quiet origin/main -- "$f"; then # a deleted file differs too
      echo "error: $f exists on main and changed or was deleted; bump the database version and add a migration (ADR-0025)" >&2
      fail=1
    fi
  done
fi

exit "$fail"
