#!/usr/bin/env python3
"""Coverage ratchet for sdk/sdk-android and sdk-ios (ADR-0049 decision 4, gate Q3).

The committed value is scripts/coverage-baseline.json: {"<module>": {"line": <percent>, "lowered": {"from", "reason"}}}.

  check-coverage.py android <jacoco report.xml>   PR job: line coverage of sdk-android against the committed value
  check-coverage.py ios <xccov report.json>       PR job: line coverage of the SDK sources of sdk-ios
  check-coverage.py ratchet [base-ref]            check-repo-rules.sh: the committed value was not lowered vs the base

A measure fails when it is more than 0.5 point below the committed value, or more than 0.5 point above it (the message
prints the value to commit, so a rise is locked in by the same PR). `ratchet` fails when a module's value is below the
value at the merge base with the base ref (default origin/main) unless the same file sets `lowered: {from, reason}`
with `from` equal to the base value and a reason. It reads files, not commit messages, so it works in the pre-commit
hook and in a depth-1 CI checkout. No base file, or no such module on the base: skipped (the PR that adds the file).

Test: python3 -I -m unittest discover -s scripts -p 'test_*.py'
"""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BAND = 0.5
FILE = "scripts/coverage-baseline.json"


def percent(covered, total):
    return round(100.0 * covered / total, 2) if total else 0.0


def jacoco_line(path):
    """Line coverage of the whole JaCoCo XML report: the LINE counter that is a direct child of the root."""
    for c in ET.parse(path).getroot().findall("counter"):
        if c.get("type") == "LINE":
            covered = int(c.get("covered"))
            return percent(covered, covered + int(c.get("missed")))
    raise SystemExit(f"error: {path} has no report-level LINE counter")


def xccov_line(path):
    """Line coverage of the target `SignalDock` in `xcrun xccov view --report --json`, files under sdk-ios/Sources.
    The target `SignalDockTests` lists the same files again, so it is not read."""
    covered = total = 0
    for target in json.load(open(path)).get("targets", []):
        for f in target.get("files", []) if target.get("name") == "SignalDock" else []:
            if "/sdk-ios/Sources/" in f.get("path", ""):
                covered += f["coveredLines"]
                total += f["executableLines"]
    if not total:
        raise SystemExit(f"error: {path} has no file under sdk-ios/Sources")
    return percent(covered, total)


def measure(module, value, committed):
    """Error message list: empty when value is within the band of the committed one."""
    base = committed[module]["line"]
    if value < base - BAND:
        return [f"{module}: line coverage {value}% is more than {BAND} point below the committed {base}% ({FILE}). Add tests."]
    if value > base + BAND:
        return [f"{module}: line coverage {value}% is more than {BAND} point above the committed {base}%. "
                f'Commit "line": {value} for {module} in {FILE} to lock the rise in.']
    return []


def ratchet(now, before):
    errors = []
    for module, cur in now.items():
        if module not in before or cur["line"] >= before[module]["line"]:
            continue
        low = cur.get("lowered") or {}
        if low.get("from") != before[module]["line"] or not str(low.get("reason", "")).strip():
            errors.append(f'{module}: committed line coverage fell from {before[module]["line"]}% to {cur["line"]}%. '
                          f'Set "lowered": {{"from": {before[module]["line"]}, "reason": "<why>"}} next to it in {FILE}, or keep the value.')
    return errors


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True)


def base_file(base):
    if git("rev-parse", "--verify", "-q", base).returncode != 0:
        raise SystemExit(f"error: base ref {base} does not exist; fetch it before this check")
    mb = git("merge-base", "HEAD", base)  # a shallow CI checkout has none: the ref itself is the base
    ref = mb.stdout.strip() if mb.returncode == 0 and mb.stdout.strip() else base
    shown = git("show", f"{ref}:{FILE}")
    return json.loads(shown.stdout) if shown.returncode == 0 else None


def committed():
    try:
        data = json.load(open(FILE))
        for module, v in data.items():
            float(v["line"])
        return data
    except (OSError, ValueError, KeyError, TypeError) as e:
        raise SystemExit(f"error: {FILE} is missing or malformed ({e!r}); each module needs a numeric \"line\"")


def main(argv):
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd in ("android", "ios") and len(argv) == 3:
        module = "sdk-android" if cmd == "android" else "sdk-ios"
        value = (jacoco_line if cmd == "android" else xccov_line)(argv[2])
        data = committed()
        if module not in data:
            raise SystemExit(f"error: {FILE} has no entry for {module}")
        print(f"{module}: line coverage {value}% (committed {data[module]['line']}%)")
        errors = measure(module, value, data)
    elif cmd == "ratchet":
        before = base_file(argv[2] if len(argv) > 2 else "origin/main")
        errors = ratchet(json.loads(git("show", f":{FILE}").stdout), before) if before is not None else []
    else:
        raise SystemExit(__doc__)
    for e in errors:
        print("error:", e, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
