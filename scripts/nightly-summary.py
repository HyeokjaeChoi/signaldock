#!/usr/bin/env python3
"""Gate Q2 (ADR-0049): write the JSON summary of one nightly job. Python 3 standard library only.

Format and rules: docs/engineering/nightly-summary.md. Every job of .github/workflows/nightly.yml
runs this in a step with `if: always()`, then uploads the folder as the artifact `nightly-summary-<job>`.

  nightly-summary.py --job e2e-delivery --gate Q1 --status "$JOB_STATUS" \
      --cases e2e-cases.json --number emulatorApi=34 [--out nightly-summary]

--status is the `job.status` context (success, failure, cancelled). The result is "pass" only when
the status is success and every case passed. A --cases file that is missing makes the result "fail".
"""
import argparse
import json
import os
import sys

SCHEMA = 1


def parse_number(text):
    key, sep, value = text.partition("=")
    if not sep or not key:
        raise ValueError(f"--number needs key=value, got {text!r}")
    for cast in (int, float):
        try:
            return key, cast(value)
        except ValueError:
            pass
    raise ValueError(f"--number value is not a number: {text!r}")


def load_cases(path):
    """Returns (cases, complete). A missing or malformed file is incomplete: the job never reached its result."""
    if path is None:
        return [], True
    try:
        with open(path, encoding="utf-8") as f:
            cases = json.load(f)
    except (FileNotFoundError, ValueError):
        return [], False
    if not isinstance(cases, list) or not all(isinstance(c, dict) and "result" in c for c in cases):
        return [], False
    return cases, True


def build(job, gate, status, cases, complete, numbers, env):
    ok = status == "success" and complete and all(c["result"] == "pass" for c in cases)
    server, repo, run = env.get("GITHUB_SERVER_URL"), env.get("GITHUB_REPOSITORY"), env.get("GITHUB_RUN_ID")
    return {
        "schema": SCHEMA,
        "job": job,
        "gate": gate,
        "commit": env.get("GITHUB_SHA", ""),
        "run": {
            "id": run or "",
            "attempt": env.get("GITHUB_RUN_ATTEMPT", ""),
            "event": env.get("GITHUB_EVENT_NAME", ""),
            "url": f"{server}/{repo}/actions/runs/{run}" if server and repo and run else "",
        },
        "result": "pass" if ok else "fail",
        "cases": cases,
        "numbers": numbers,
    }


def main(argv, env):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--job", required=True)
    p.add_argument("--gate", required=True)
    p.add_argument("--status", required=True)
    p.add_argument("--cases")
    p.add_argument("--number", action="append", default=[])
    p.add_argument("--out", default="nightly-summary")
    a = p.parse_args(argv)
    cases, complete = load_cases(a.cases)
    summary = build(a.job, a.gate, a.status, cases, complete, dict(parse_number(n) for n in a.number), env)
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, f"{a.job}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")
    print(f"{path}: {summary['result']}")


if __name__ == "__main__":
    main(sys.argv[1:], os.environ)
