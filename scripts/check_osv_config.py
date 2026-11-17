#!/usr/bin/env python3
"""Gate R2 (ADR-0049 decision 9): the osv-scanner.toml files. Python 3 standard library only.

Run from the repo root: check_osv_config.py   (reads the git index)

Every tracked osv-scanner.toml has only [[IgnoredVulns]] tables (no package-wide override), and each has an id, a reason,
and an `ignoreUntil` date at most 30 days after today (UTC; for a new entry that is the commit date). A date in the past
is allowed: OSV-Scanner then reports the finding again. The file is read line by line (python3 of a developer machine may
lack tomllib); a line that is not a comment, a table header, or `key = value` is an error.

scripts/osv-scan.sh gives each lockfile the osv-scanner.toml of its own folder (a finding is ignored in one tree only),
so every config must sit next to a lockfile that the LOCKFILES array of the script lists; any other one would be ignored
silently. Every tracked npm lockfile and Gradle verification-metadata.xml must be listed in that array.
"""
import datetime
import posixpath
import re
import subprocess
import sys

SCAN = "scripts/osv-scan.sh"


def config_errors(text, today):
    errors, entries, cur = [], [], None
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line == "[[IgnoredVulns]]":
            cur = {"line": n}
            entries.append(cur)
        elif re.match(r"^\[", line):
            errors.append(f"line {n}: only [[IgnoredVulns]] is allowed, found {line}")
            cur = None
        elif cur is not None and (m := re.match(r"^(id|reason|ignoreUntil)\s*=\s*(.+)$", line)):
            cur[m.group(1)] = m.group(2).strip()
        else:
            errors.append(f"line {n}: not a comment, an [[IgnoredVulns]] header, or an id, reason, or ignoreUntil assignment")
    for e in entries:
        where = f"entry at line {e['line']}"
        if not re.fullmatch(r'"[A-Za-z0-9._-]+"', e.get("id", "")):
            errors.append(f"{where}: needs id = \"...\"")
        if not re.fullmatch(r'"[^"]{15,}"', e.get("reason", "")):
            errors.append(f"{where}: needs a reason of at least 15 characters")
        until = e.get("ignoreUntil", "")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", until):
            errors.append(f"{where}: needs ignoreUntil = YYYY-MM-DD")
        else:
            try:
                d = datetime.date.fromisoformat(until)
            except ValueError:
                errors.append(f"{where}: ignoreUntil {until} is not a date")
            else:
                if d > today + datetime.timedelta(days=30):
                    errors.append(f"{where}: ignoreUntil {until} is more than 30 days after {today}")
    return errors


def lockfiles(scan_text):
    """The entries of the `LOCKFILES=( ... )` bash array, or None when there is no such array."""
    m = re.search(r"^LOCKFILES=\(\s*$(.*?)^\)", scan_text, re.M | re.S)
    if not m:
        return None
    return [line.split("#")[0].strip() for line in m.group(1).splitlines() if line.split("#")[0].strip()]


def repo_errors(scan_text, configs, tracked_lockfiles, today):
    """`configs` maps each tracked osv-scanner.toml path to its text."""
    listed = lockfiles(scan_text)
    if not listed:
        return [f"no LOCKFILES=( ... ) array found in {SCAN} (ADR-0049 decision 9)"]
    errors = []
    folders = {posixpath.dirname(e) for e in listed if e.endswith(("package-lock.json", "verification-metadata.xml"))}
    for cfg, text in configs.items():
        if posixpath.dirname(cfg) not in folders:
            errors.append(f"{cfg} is next to no lockfile in LOCKFILES of {SCAN}, so it has no effect (ADR-0049 decision 9)")
        errors += [f"{cfg} {e}" for e in config_errors(text, today)]
    for f in tracked_lockfiles:
        if f not in listed:
            errors.append(f"{f} is tracked but not listed in LOCKFILES of {SCAN} (ADR-0049 decision 9)")
    return errors


def show(path):
    r = subprocess.run(["git", "show", ":" + path], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def tracked_files(*patterns):
    out = subprocess.run(["git", "ls-files", "-z", "--", *patterns], capture_output=True, text=True, check=True).stdout
    return [f for f in out.split("\0") if f]


def main():
    configs = {c: show(c) or "" for c in tracked_files("osv-scanner.toml", "*/osv-scanner.toml")}
    locks = tracked_files("package-lock.json", "*/package-lock.json", "gradle/verification-metadata.xml", "*/gradle/verification-metadata.xml")
    today = datetime.datetime.now(datetime.timezone.utc).date()
    errors = repo_errors(show(SCAN) or "", configs, locks, today)
    for e in errors:
        print(f"error: {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
