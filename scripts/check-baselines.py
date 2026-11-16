#!/usr/bin/env python3
"""A static analysis baseline may only lose entries (ADR-0049 decision 3).

Compares each tracked `lint-baseline.xml` (Android Lint) and `detekt-baseline.xml` (detekt) in the git index with the
same file on the base: the merge base of HEAD and a ref (default origin/main), so a main that moved on does not count
against a branch that did not touch the baseline. Fails when a baseline file has more entries of one rule in one
source file than the base file has. A baseline file that is new on the branch counts as empty on the base, so a new
module starts with no entries.

An entry is counted by rule and file only. Line numbers, message wording, and the name of the enclosing declaration are
not part of it, so moving code, renaming a function, or a tool update that rewords a message does not make an entry
new. One more finding of a rule in a file does. The cost: a fix and a new finding of the same rule in the same file
net to zero.

Bootstrap: when the base has no file of a kind, that kind is skipped (the PR that adds the first baselines).

Usage: scripts/check-baselines.py [base-ref]       (run from the repository root; check-repo-rules.sh does)
Test:  python3 -I -m unittest discover -s scripts -p 'test_*.py'
"""
import re
import subprocess
import sys
from collections import Counter

KINDS = ("lint-baseline.xml", "detekt-baseline.xml")


def attr(chunk, name):
    m = re.search(r'\b' + name + r'="([^"]*)"', chunk)
    return m.group(1) if m else ""


def entries(kind, text):
    """The entries of one baseline file as a Counter of (rule, file)."""
    if kind == "detekt-baseline.xml":
        # <ID>Rule:File.kt:signature</ID>; the signature may hold colons, so split twice at most.
        return Counter(tuple((i.split(":", 2) + ["", ""])[:2]) for i in re.findall(r"<ID>(.*?)</ID>", text, re.S))
    # Lint: one <issue ...> element per entry; `(?=\s)` skips the <issues ...> root element.
    return Counter(
        (attr(c, "id"), attr(c, "file"))
        for c in re.split(r"<issue(?=\s)", text)[1:]
    )


def git(*args, cwd=None):
    return subprocess.run(["git", *args], capture_output=True, text=True, cwd=cwd)


def check(base, cwd=None):
    """Return the list of error messages (empty when no baseline has more entries than on the base)."""
    if git("rev-parse", "--verify", "-q", base, cwd=cwd).returncode != 0:
        return [f"base ref {base} does not exist; fetch it before this check"]
    # In a shallow CI checkout there is no merge base; the ref itself is then the base.
    mb = git("merge-base", "HEAD", base, cwd=cwd)
    ref = mb.stdout.strip() if mb.returncode == 0 and mb.stdout.strip() else base
    on_base = git("ls-tree", "-r", "--name-only", ref, cwd=cwd).stdout.splitlines()
    tracked = git("ls-files", "-z", "--", *("*" + k for k in KINDS), cwd=cwd).stdout.split("\0")
    errors = []
    for kind in KINDS:
        if not any(p.rsplit("/", 1)[-1] == kind for p in on_base):
            continue  # bootstrap: nothing of this kind on the base yet
        for path in sorted(p for p in tracked if p and p.rsplit("/", 1)[-1] == kind):
            new = entries(kind, git("show", ":" + path, cwd=cwd).stdout)
            old_text = git("show", f"{ref}:{path}", cwd=cwd)
            old = entries(kind, old_text.stdout) if old_text.returncode == 0 else Counter()
            for (rule, source), count in sorted((new - old).items()):
                errors.append(
                    f"{path} has {new[(rule, source)]} entries of {rule} in {source}; {base} has {old[(rule, source)]}"
                    + (f" (+{count})" if count > 1 else "")
                )
    return errors


if __name__ == "__main__":
    problems = check(sys.argv[1] if len(sys.argv) > 1 else "origin/main")
    for p in problems:
        print("error: " + p, file=sys.stderr)
    if problems:
        print("error: a baseline may only lose entries (ADR-0049 decision 3); fix the finding, do not baseline it", file=sys.stderr)
    sys.exit(1 if problems else 0)
