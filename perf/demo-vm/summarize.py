"""Summarizes one stage of the demo VM measurement (perf/demo-vm/README.md).

Run: python3 summarize.py <dir>/rate-<N>   -> prints one JSON line and writes stage.json there.
Uses only the measure phase of req_ms. Container CPU comes from stats.csv (docker stats, 5 s samples; % of one CPU).
"""
import collections
import gzip
import json
import os
import sys


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    return round(xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))], 1)


d = sys.argv[1]
durs, ok, outcomes = [], [], collections.Counter()
with gzip.open(os.path.join(d, "raw.json.gz"), "rt") as f:
    for line in f:
        r = json.loads(line)
        if r.get("type") != "Point" or r.get("metric") != "req_ms" or r["data"]["tags"].get("phase") != "measure":
            continue
        v, o = r["data"]["value"], r["data"]["tags"].get("outcome")
        durs.append(v)
        outcomes[o] += 1
        if o == "ok":
            ok.append(v)

m = json.load(open(os.path.join(d, "summary.json")))["metrics"]
cpu = collections.defaultdict(list)
for line in open(os.path.join(d, "stats.csv")):
    parts = line.strip().split(",")
    if len(parts) >= 3 and parts[2].endswith("%"):
        cpu[parts[1]].append(float(parts[2][:-1]))

n = len(durs)
out = {
    "rate": int(os.path.basename(d).split("-")[1]),
    "salt": open(os.path.join(d, "salt")).read().strip(),
    "measure_requests": n,
    "outcomes": dict(outcomes),
    "non_ok_share": round(1 - outcomes["ok"] / n, 4) if n else None,
    "p50_ms": pct(durs, 50), "p95_ms": pct(durs, 95), "p99_ms": pct(durs, 99), "max_ms": round(max(durs), 1) if durs else None,
    "p95_ok_ms": pct(ok, 95),
    "dropped_iterations": (m.get("dropped_iterations") or {}).get("count", 0),
    "cpu_pct": {k: {"mean": round(sum(v) / len(v), 1), "max": round(max(v), 1), "samples": len(v)} for k, v in sorted(cpu.items())},
}
json.dump(out, open(os.path.join(d, "stage.json"), "w"), indent=1)
print(json.dumps(out))
