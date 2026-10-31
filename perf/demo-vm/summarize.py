"""Summarizes one stage of the demo VM measurement (perf/demo-vm/README.md).

Run: python3 summarize.py <dir>/rate-<N>   -> prints one JSON line and writes stage.json there.
Uses only the measure phase of req_ms. Container CPU comes from stats.csv (docker stats, 5 s samples; % of one CPU)
and covers only the measure window: [start + WARMUP_S, start + WARMUP_S + MEASURE_S] (env, default 30 and 180).
The run start is the earliest Point time in raw.json.gz. `k6_cpu_max_full_run` is the k6 CPU max over all rows of stats.csv.
"""
import collections
import datetime as dt
import gzip
import json
import os
import re
import sys
from pathlib import Path


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    return round(xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))], 1)


d = sys.argv[1]
durs, ok, outcomes = [], [], collections.Counter()
start = None
with gzip.open(os.path.join(d, "raw.json.gz"), "rt") as f:
    for line in f:
        r = json.loads(line)
        if r.get("type") == "Point":
            # k6 writes nanoseconds; fromisoformat needs exactly 6 fraction digits.
            t = dt.datetime.fromisoformat(re.sub(r"\.(\d+)", lambda m: "." + m[1][:6].ljust(6, "0"), r["data"]["time"]).replace("Z", "+00:00")).timestamp()
            start = t if start is None else min(start, t)
        if r.get("type") != "Point" or r.get("metric") != "req_ms" or r["data"]["tags"].get("phase") != "measure":
            continue
        v, o = r["data"]["value"], r["data"]["tags"].get("outcome")
        durs.append(v)
        outcomes[o] += 1
        if o == "ok":
            ok.append(v)

m = json.loads(Path(d, "summary.json").read_text())["metrics"]
warm, measure = float(os.environ.get("WARMUP_S", 30)), float(os.environ.get("MEASURE_S", 180))
lo, hi = start + warm, start + warm + measure
cpu, k6_full = collections.defaultdict(list), []
for line in Path(d, "stats.csv").read_text().splitlines():
    parts = line.strip().split(",")
    if len(parts) >= 3 and parts[2].endswith("%"):
        v = float(parts[2][:-1])
        if parts[1] == "sd-k6":
            k6_full.append(v)
        if lo <= int(parts[0]) <= hi:
            cpu[parts[1]].append(v)

n = len(durs)
out = {
    "rate": int(os.path.basename(d).split("-")[1]),
    "salt": Path(d, "salt").read_text().strip(),
    "measure_requests": n,
    "outcomes": dict(outcomes),
    "non_ok_share": round(1 - outcomes["ok"] / n, 4) if n else None,
    "p50_ms": pct(durs, 50), "p95_ms": pct(durs, 95), "p99_ms": pct(durs, 99), "max_ms": round(max(durs), 1) if durs else None,
    "p95_ok_ms": pct(ok, 95),
    "dropped_iterations": (m.get("dropped_iterations") or {}).get("count", 0),
    "k6_cpu_max_full_run": round(max(k6_full), 1) if k6_full else None,
    "cpu_pct": {k: {"mean": round(sum(v) / len(v), 1), "max": round(max(v), 1), "samples": len(v)} for k, v in sorted(cpu.items())},
}
Path(d, "stage.json").write_text(json.dumps(out, indent=1))
print(json.dumps(out))
