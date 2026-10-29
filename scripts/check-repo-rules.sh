#!/bin/bash
# Fast static checks for review findings that recurred on PR #9.
# Runs from .githooks/pre-commit and .githooks/pre-push. Reads the git index,
# so it checks what will be committed, not the working tree.
set -euo pipefail
# git grep output goes to stderr, which is a terminal in hooks, so git would
# open an empty pager and the push would wait on "(END)".
export GIT_PAGER=cat

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

# The two freeze checks below compare with origin/main. In CI a missing ref must fail the check, not skip it:
# the workflow fetches it (a shallow checkout has no origin/main).
HAVE_MAIN=0
if git rev-parse --verify -q origin/main >/dev/null; then
  HAVE_MAIN=1
elif [ "${CI:-}" = true ]; then
  echo "error: CI=true and origin/main is missing; the schema and migration freeze checks cannot run (fetch it before this script)" >&2
  fail=1
fi

# A released Room schema file is frozen (ADR-0025). A schema change needs a new version, a migration, and a new N.json.
if [ "$HAVE_MAIN" = 1 ]; then
  for f in $(git ls-tree -r --name-only origin/main -- sdk/sdk-android/schemas); do
    if ! git diff --quiet origin/main -- "$f"; then # a deleted file differs too
      echo "error: $f exists on main and changed or was deleted; bump the database version and add a migration (ADR-0025)" >&2
      fail=1
    fi
  done
fi

# A released iOS SQL migration is frozen (ADR-0047 D7, the iOS form of ADR-0025). A schema change needs a new V<n>__*.sql file.
if [ "$HAVE_MAIN" = 1 ]; then
  for f in $(git ls-tree -r --name-only origin/main -- sdk-ios/Sources/SignalDock/Migrations); do
    if ! git diff --quiet origin/main -- "$f"; then # a deleted file differs too
      echo "error: $f exists on main and changed or was deleted; add a new migration file (ADR-0047 D7)" >&2
      fail=1
    fi
  done
fi

# Workflow security (cloud-demo spec D9, ADR-0043 item 4). Reads the index. `wf` prints a workflow
# without comments or blank lines, so a trigger or a pin named only in a comment does not count.
wf() { git show ":$1" | sed -E 's/(^|[[:space:]])#.*//' | awk 'NF'; }
# `on` may be quoted (YAML 1.1 reads a bare on as a boolean, so some files quote it).
ONK='^("on"|\047on\047|on):'
# Every action is pinned by a 40-hex commit SHA; only local ./ actions are exempt (docker:// is not).
# `uses` is found as a block key, in a flow mapping ({ uses: x }), and as a quoted key.
# wfb: wf without block-scalar bodies (`run: |`, `>-`), whose lines are shell text, not YAML keys (issue 18).
# YAML quote and flow state is not tracked, so a `k: |` text inside a multi-line quoted or flow scalar can start a
# false skip. Fail-closed rule instead: a skipped line stays visible when `uses` or an explicit-key `?` starts a token
# on it (after the line start, a dash, a comma, or a bracket, and an optional anchor or tag), so no real key hides; `echo uses:` has a word before it and is skipped.
wfb() { wf "$1" | awk 'BEGIN { blk = -1 } {
  match($0, /^ */)
  if (blk >= 0 && RLENGTH > blk) { if ($0 ~ /(^|[,{[])[[:space:]]*(-[[:space:]]+)*([&!][^ ]*[[:space:]]+)*(\?([[:space:]]|$)|["\047]?uses["\047]?[[:space:]]*:)/) print; next }
  blk = -1
  K = "(\"[^\"]*\"|\047[^\047]*\047|[^ #{[\"\047,][^:]*)"
  # Header: `key: |`, `- key: |` or `- |`, with an optional anchor or tag. A bare `|` line is a plain-scalar continuation.
  if ($0 ~ ("^ *((- +)+(" K ":[ ]+)?|" K ":[ ]+)([&!][^ ]*[ ]+)*[|>][-+0-9]*[ ]*$")) {
    k = $0; sub(/^ *(- +)*/, "", k); blk = length($0) - length(k)   # column of the key, so sibling keys stay visible
    if (k ~ /^([&!][^ ]*[ ]+)*[|>]/) { p = substr($0, 1, blk); sub(/ +$/, "", p); blk = length(p) - 1 }   # `- |`: column of the dash
  }
  print }'; }
check_pins() {
  local body; body="$(wfb "$1")"
  # An explicit key (`? uses` then `: x@v4`) hides the value from the loop below (issue 17); reject any explicit key.
  if grep -E '(^|[[{,])[[:space:]]*(-[[:space:]]+)*\?([[:space:]]|$)' <<<"$body" >/dev/null; then
    echo "error: $1 has a YAML explicit key (?); write plain keys, for example uses: <action>@<sha> on one line" >&2; fail=1
  fi
  # A uses key with no value on its line (the value on the next line) passes the loop below unchecked.
  if grep -E "(^|[{,[:space:]])[\"']?uses[\"']?[[:space:]]*:[[:space:]]*(\$|[,}])" <<<"$body" >/dev/null; then
    echo "error: $1 has a uses key with no value on its line; write the value on the same line" >&2; fail=1
  fi
  while IFS= read -r ref; do
    ref="$(echo "$ref" | sed -E "s/^[\"']//; s/[\"'][[:space:]]*\$//; s/[[:space:]]+\$//")"
    case "$ref" in ./*) continue ;; esac
    if ! echo "$ref" | grep -qE '^[^@[:space:]]+@[0-9a-f]{40}$'; then
      echo "error: $1 uses '$ref', which is not pinned by a 40-character commit SHA" >&2; fail=1
    fi
  # grep -o finds every uses key on a line (a one-line flow sequence can hold several).
  done < <(grep -oE "(^|[{,[:space:]])[\"']?uses[\"']?[[:space:]]*:[[:space:]]*[^,}]+" <<<"$body" \
    | sed -E "s/^.*uses[\"']?[[:space:]]*:[[:space:]]*//" || true)
}
for f in $(git ls-files -- 'action.yml' 'action.yaml' '*/action.yml' '*/action.yaml'); do
  check_pins "$f"
done
for f in $(git ls-files -- '.github/workflows/*.yml' '.github/workflows/*.yaml'); do
  check_pins "$f"
  # No pull_request_target or workflow_run trigger: both run with secrets on code the repo does not control.
  if wf "$f" | awk -v k="$ONK" '$0 ~ k {p=1} /^[^ ]/ && $0 !~ k {p=0} p' | grep -E '(^|[^A-Za-z_])(pull_request_target|workflow_run)([^A-Za-z_]|$)' >/dev/null; then
    echo "error: $f triggers on pull_request_target or workflow_run" >&2; fail=1
  fi
  # A top-level permissions: key, so a new job never inherits the repository default token.
  # grep without -q reads all input, so pipefail never sees a SIGPIPE from the writer.
  if ! wf "$f" | grep '^permissions:' >/dev/null; then
    echo "error: $f has no top-level permissions: key" >&2; fail=1
  fi
done

# demo-deploy.yml runs only on a push of demo-v* tags, release-ios.yml only on a push of ios-v* tags (ADR-0043 item 4;
# ADR-0047 D11). A wider trigger could run the file on the Android v*.*.* tags or on a branch.
# demo-deploy.yml also has no OIDC token, no write-all, and no secrets context, even in comments.
# ponytail: skipped when a file is absent (teardown deletes demo-deploy.yml); a rename escapes this check.
for pair in .github/workflows/demo-deploy.yml:demo-v .github/workflows/release-ios.yml:ios-v; do
  wfile="${pair%%:*}"
  tagprefix="${pair##*:}"
  if git cat-file -e ":$wfile" 2>/dev/null; then
    msgs="$(wf "$wfile" | awk -v k="$ONK" -v pre="^$tagprefix" '
      function items(s,   n, a, i, v) {
        gsub(/[\[\]]/, "", s); n = split(s, a, ",")
        for (i = 1; i <= n; i++) {
          v = a[i]; sub(/^[ ]*-?[ ]*/, "", v); gsub(/["\047 ]/, "", v)
          if (v != "" && v !~ pre) print "tag pattern \047" v "\047 does not start with " substr(pre, 2)
        }
      }
      $0 ~ k { if ($0 !~ k "[ ]*$") print "on: must be a block with only push:"; p = 1; next }
      /^[^ ]/ { p = 0 }
      !p { next }
      { match($0, /^ */); ind = RLENGTH; t = substr($0, ind + 1)
        if (!i1) i1 = ind
        if (ind == i1) { if (t != "push:") print "trigger other than push: " t; next }
        if (!i2) i2 = ind
        if (ind == i2) {
          intags = (t ~ /^tags:/)
          if (!intags) { print "push: has key other than tags: " t; next }
          ntags++; v = t; sub(/^tags:[ ]*/, "", v); items(v); next }
        if (intags) items(t)
      }
      END { if (!ntags) print "push: has no tags:" }')"
    if [ -n "$msgs" ]; then
      echo "$msgs" | sed "s|^|error: $wfile: |" >&2
      fail=1
    fi
    if [ "$tagprefix" = demo-v ] && git show ":$wfile" | grep -niE 'id-token|write-all|(^|[^A-Za-z0-9_-])secrets([^A-Za-z0-9_-]|$)' >&2; then
      echo "error: $wfile above must not use id-token, write-all, or the secrets context (the VM pulls images; GitHub holds no credentials)" >&2
      fail=1
    fi
  fi
done

# The iOS release ZIP removes the SignalDockTests line from Package.swift (ADR-0047 Ticket 13). If the manifest changes
# that line, the release would fail only at tag time; this finds it in the PR.
scripts/build-ios-release-zip.sh --check-manifest > /dev/null || fail=1

# The wire constant SDK_VERSION must equal sdkVersion of the Maven publication (release.sh and the tag use sdkVersion).
sdk_gradle_version="$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' sdk/sdk-android/build.gradle.kts)"
sdk_const_version="$(sed -n 's/^internal const val SDK_VERSION = "\(.*\)"$/\1/p' sdk/sdk-android/src/main/kotlin/local/signaldock/internal/transport/Versions.kt)"
if [ -z "$sdk_gradle_version" ] || [ "$sdk_gradle_version" != "$sdk_const_version" ]; then
  echo "error: SDK_VERSION '$sdk_const_version' in Versions.kt differs from sdkVersion '$sdk_gradle_version' in sdk/sdk-android/build.gradle.kts" >&2
  fail=1
fi

# iOS SDK (ADR-0047 D7, D13): no package dependency in sdk-ios/Package.swift, and no `privacy: .public` log interpolation.
# The patterns use \s, so a call split across lines is also found (issue 22); the samples keep that true.
if ! python3 - <<'PY'
import re, subprocess, sys

PACKAGE = re.compile(r"\.package\s*\(")
PUBLIC = re.compile(r"privacy:\s*\.public")
# Regression samples: each must match, on one line and split across lines.
for rx, ok in ((PACKAGE, (".package(", ".package\n  (")), (PUBLIC, ("privacy: .public", "privacy:\n  .public"))):
    for sample in ok:
        if not rx.search(sample):
            sys.exit("error: check-repo-rules.sh multi-line sample no longer matches: " + repr(sample))

def files(*pathspec):
    out = subprocess.run(["git", "ls-files", "-z", "--", *pathspec], capture_output=True, text=True, check=True).stdout
    return [f for f in out.split("\0") if f]

def scan(rx, path):
    text = subprocess.run(["git", "show", ":" + path], capture_output=True, text=True, check=True).stdout
    return [(path, text.count("\n", 0, m.start()) + 1) for m in rx.finditer(text)]

bad = False
for path, line in [h for f in files("sdk-ios/Package*.swift") for h in scan(PACKAGE, f)]:
    print(f"{path}:{line}", file=sys.stderr)
    print("error: a sdk-ios/Package*.swift manifest declares a package dependency above; the iOS SDK has no external dependency (ADR-0047 D13)", file=sys.stderr)
    bad = True
for path, line in [h for f in files("sdk-ios") for h in scan(PUBLIC, f)]:
    print(f"{path}:{line}", file=sys.stderr)
    print("error: privacy: .public above; log values stay private (ADR-0047 D13)", file=sys.stderr)
    bad = True
sys.exit(1 if bad else 0)
PY
then
  fail=1
fi

# iOS privacy manifest (ADR-0047 D14): the manifest exists, and every required-reason API that sdk-ios/Sources uses has its
# category in it. Names and categories: https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype
ios_manifest=sdk-ios/Sources/SignalDock/PrivacyInfo.xcprivacy
if ! git cat-file -e ":$ios_manifest" 2> /dev/null; then
  echo "error: $ios_manifest is missing (ADR-0047 D14)" >&2
  fail=1
fi
ios_manifest_text="$(git show ":$ios_manifest" 2> /dev/null || true)"
# Format: category|extended regex. C calls match as whole words followed by `(`; Swift names match as substrings, with
# both cases of the first letter (`creationDate`, `NSFileCreationDate`, `contentModificationDateKey`). Only code files are
# scanned (.swift, .c, .h, .m), and a line that starts with `//` is skipped. Apple lists `getattrlist`, `fgetattrlist`, and
# `getattrlistat` under both FileTimestamp and DiskSpace, so a use of them needs both categories in the manifest.
ios_reason_apis='FileTimestamp|[Cc]reationDate|[Mm]odificationDate
FileTimestamp|(^|[^A-Za-z0-9_])(stat|fstat|fstatat|lstat|getattrlist|getattrlistbulk|fgetattrlist|getattrlistat)[[:space:]]*\(
SystemBootTime|systemUptime|mach_absolute_time
DiskSpace|statvfs|statfs|volumeAvailableCapacity|volumeTotalCapacity|systemFreeSize|systemSize
DiskSpace|(^|[^A-Za-z0-9_])(fstatfs|fstatvfs|getattrlist|fgetattrlist|getattrlistat)[[:space:]]*\(
UserDefaults|UserDefaults
ActiveKeyboards|activeInputModes'
while IFS='|' read -r category pattern; do
  rc=0
  raw="$(git grep --cached -nE "$pattern" -- 'sdk-ios/Sources/*.swift' 'sdk-ios/Sources/*.[chm]')" || rc=$?
  if [ "$rc" -ge 2 ]; then
    echo "error: git grep failed (exit $rc) for the $category required-reason check" >&2
    fail=1
    continue
  fi
  hits="$(grep -Ev '^[^:]*:[0-9]+:[[:space:]]*//' <<< "$raw" || true)"
  if [ -n "$hits" ] && ! grep -q "<string>NSPrivacyAccessedAPICategory$category</string>" <<< "$ios_manifest_text"; then
    echo "$hits" >&2
    echo "error: sdk-ios/Sources uses a $category required-reason API above, but $ios_manifest has no NSPrivacyAccessedAPICategory$category entry (ADR-0047 D14)" >&2
    fail=1
  fi
done <<< "$ios_reason_apis"

exit "$fail"
