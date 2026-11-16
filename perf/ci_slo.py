#!/usr/bin/env python3
"""Verdict of gate B3 (ADR-0049 amendment, ticket 08). Called by perf/ci-slo.sh. Standard library only.

  ci_slo.py --summary k6-summary.json --out result.json --baseline perf/ci-slo-baseline.json --runner-class ubuntu-latest
  ci_slo.py --summary k6-summary.json --out result.json --measure --runner-class ubuntu-latest   (no p95 rule: measures a baseline)

Fails on any bad request, any dropped iteration, too few requests, or p95 > 2x the baseline of the same runner class.
Writes `result.json` and, next to it, `cases.json` for scripts/nightly-summary.py (gate Q2).
"""
import argparse
import json
import os
import sys

FACTOR = 2


def verdict(summary, baseline, runner_class, expected_requests):
    """Returns the result dict. `baseline` None means measure mode (no p95 rule). p95 and the request count are of the measure phase."""
    m = summary["metrics"]
    p95 = m["http_req_duration{phase:measure}"]["p(95)"]
    reqs = int(m["http_reqs{phase:measure}"]["count"])
    bad = int(m.get("non200", {}).get("count", 0))
    dropped = int(m.get("dropped_iterations", {}).get("count", 0))
    reasons = []
    if bad:
        reasons.append(f"{bad} bad requests (status not 200, or an event not accepted)")
    if dropped:
        reasons.append(f"{dropped} dropped iterations (the generator could not keep the rate)")
    if reqs < expected_requests * 0.99:
        reasons.append(f"{reqs} requests, expected about {expected_requests}")
    limit = None
    if baseline is not None:
        if baseline["runnerClass"] != runner_class:
            reasons.append(f"baseline is for runner class {baseline['runnerClass']!r}, this run is {runner_class!r}")
        else:
            limit = FACTOR * baseline["p95Ms"]
            if p95 > limit:
                reasons.append(f"p95 {p95:.1f} ms > {FACTOR} x baseline {baseline['p95Ms']} ms = {limit:.1f} ms")
    return {"result": "fail" if reasons else "pass", "reasons": reasons, "p95Ms": round(p95, 2), "limitMs": limit,
            "requests": reqs, "bad": bad, "dropped": dropped, "runnerClass": runner_class}


def main(argv, env):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--summary", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--baseline")
    ap.add_argument("--measure", action="store_true")
    ap.add_argument("--runner-class", required=True)
    ap.add_argument("--seconds", type=float, default=0, help="wall time of the k6 run, for the nightly summary")
    ap.add_argument("--expected-requests", type=int, default=2400)
    a = ap.parse_args(argv)
    if bool(a.baseline) == a.measure:
        ap.error("give exactly one of --baseline and --measure")
    with open(a.summary, encoding="utf-8") as f:
        summary = json.load(f)
    baseline = None
    if a.baseline:
        with open(a.baseline, encoding="utf-8") as f:
            baseline = json.load(f)
    r = verdict(summary, baseline, a.runner_class, a.expected_requests)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(r, f, indent=2)
        f.write("\n")
    with open(os.path.join(os.path.dirname(a.out) or ".", "cases.json"), "w", encoding="utf-8") as f:
        json.dump([{"name": "ingest-slo", "result": r["result"], "seconds": round(a.seconds, 1)}], f)
    if env.get("GITHUB_ENV"):  # read by the summary step of nightly.yml
        with open(env["GITHUB_ENV"], "a", encoding="utf-8") as f:
            f.write(f"SLO_P95_MS={r['p95Ms']}\n")
    print(json.dumps(r))
    for reason in r["reasons"]:
        print(f"FAIL: {reason}", file=sys.stderr)
    return 1 if r["result"] == "fail" else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
