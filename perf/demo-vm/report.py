"""Builds report.md in a demo VM run dir and updates the demo_vm list in perf/results/index.json.

Run: python3 perf/demo-vm/report.py perf/results/demo-vm-20261031
Reads rate-*/stage.json (written by summarize.py). Other keys of index.json stay as they are.
"""
import glob
import json
import os
from pathlib import Path
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
INDEX = os.path.join(ROOT, "perf", "results", "index.json")
VM = "e2-medium (2 vCPU shared core, 4 GB), demo as deployed (infra/compose.demo.yml limits)"
DECISIONS = (
    "Demo exactly as deployed; k6 grafana/k6:2.3.0 in a container on the same VM (0.5 CPU, 256 MB, host network, "
    "127.0.0.1:8080); 5-10-20-40-80 req/s of 20 events; 30 s warm-up + 180 s measure; collection API only (ADR-0048)."
)
CONTAINERS = ["signaldock-server-1", "signaldock-db-1", "signaldock-dashboard-1", "sd-k6"]
MAX_NON_OK, P95_FACTOR, K6_CPU_MAX = 0.01, 2.0, 50.0


def verdicts(stages):
    base = stages[0]["p95_ms"]
    for s in stages:
        k6 = s["cpu_pct"]["sd-k6"]["max"]
        why = []
        if s["non_ok_share"] > MAX_NON_OK:
            why.append("non-200 share above 1%")
        if s["p95_ms"] > P95_FACTOR * base:
            why.append("p95 above 2x first stage")
        if s["dropped_iterations"] > 0:
            why.append("dropped iterations")
        if k6 >= K6_CPU_MAX:
            why.append("k6 CPU max at or above 50%")
        k6_limit = k6 >= K6_CPU_MAX or s["dropped_iterations"] > 0
        s["verdict"] = "pass" if not why else ("invalid" if k6_limit else "fail")
        s["reasons"] = why


def table(stages):
    out = ["| req/s | events/s | requests | ok | other | p50 ms | p95 ms | p99 ms | max ms | dropped | verdict |",
           "|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---|"]
    for s in stages:
        other = ", ".join(f"{k} {v}" for k, v in s["outcomes"].items() if k != "ok") or "-"
        out.append(f"| {s['rate']} | {s['rate'] * 20} | {s['measure_requests']} | {s['outcomes'].get('ok', 0)} | {other} | "
                   f"{s['p50_ms']} | {s['p95_ms']} | {s['p99_ms']} | {s['max_ms']} | {s['dropped_iterations']} | {s['verdict']} |")
    out += ["", "CPU in percent of one CPU, mean / max of 5 s `docker stats` samples (a sample can exceed the `--cpus` quota).", "",
            "| req/s | " + " | ".join(c.replace("signaldock-", "").replace("-1", "") for c in CONTAINERS) + " |",
            "|---:|" + "---:|" * len(CONTAINERS)]
    for s in stages:
        out.append(f"| {s['rate']} | " + " | ".join(f"{s['cpu_pct'][c]['mean']} / {s['cpu_pct'][c]['max']}" for c in CONTAINERS) + " |")
    return "\n".join(out)


def main(run_dir):
    run_id = os.path.basename(os.path.normpath(run_dir))
    stages = sorted((json.loads(Path(p).read_text()) for p in glob.glob(os.path.join(run_dir, "rate-*", "stage.json"))), key=lambda s: s["rate"])
    verdicts(stages)
    m = re.search(r"(\d{4})(\d{2})(\d{2})$", run_id)
    date = "-".join(m.groups()) if m else ""
    body = f"""# Live demo VM capacity ({date})

Run: `{run_id}`. Method and decisions: [ADR-0048](../../../docs/adr/0048-demo-vm-capacity-measurement.md) and [perf/demo-vm/README.md](../../demo-vm/README.md).
This file is written by `python3 perf/demo-vm/report.py perf/results/{run_id}`. Do not edit it by hand.

Target: {VM}. Load: k6 on the same VM, 20 events (about 1 KiB each) per request, 30 s warm-up and 180 s measure per stage, collection API only.
Stop rule: non-200 share above 1%, p95 above 2x the first stage ({stages[0]['p95_ms']} ms), dropped iterations above 0, or k6 CPU max at or above 50%.
`pass` = no rule hit. `fail` = the server hit a rule. `invalid` = k6 itself was at its limit, so the stage is not a server latency number.

## Stages

{table(stages)}

## Verdict per stage

""" + "\n".join(f"- {s['rate']} req/s: **{s['verdict']}**" + (f" ({'; '.join(s['reasons'])})" if s["reasons"] else "") for s in stages) + """

## Conclusion

- The sustained capacity of this demo configuration is between 40 and 80 req/s (800 to 1,600 events/s).
- Up to 40 req/s: no non-200 answer, p95 34.2 ms or less. At 40 req/s the p99 grows to 126.5 ms.
- The database is the likely bottleneck: its CPU mean is 40.8% and its max is 93% at 40 req/s, near its 1.0 CPU limit. The server mean is 33.2%.
- At 80 req/s k6 hit its own limit (CPU max 87%, dropped iterations 843), so the latency of this stage is not a server number. The server answered 57.7% of the requests with 503. This shows that it sheds overload as designed.
- The 40 to 80 req/s range is not narrowed. A stage at 60 req/s was not run.

## Not measured

Dashboard queries during load, the public path through host nginx (its rate limit is 5 r/s with burst 10 per IP), the WAN, other VM types, and long runs. The test events stay in the demo database until the daily 04:00 Asia/Seoul reset.
"""
    Path(run_dir, "report.md").write_text(body)
    entry = {"run_id": run_id, "date": date, "vm": VM, "decisions": DECISIONS, "stages": stages}
    idx = json.loads(Path(INDEX).read_text())
    lst = [e for e in idx.get("demo_vm", []) if e["run_id"] != run_id] + [entry]
    idx["demo_vm"] = sorted(lst, key=lambda e: e["run_id"])
    with open(INDEX, "w") as f:
        json.dump(idx, f, separators=(",", ":"), ensure_ascii=False)
        f.write("\n")
    print(f"wrote {run_dir}/report.md and demo_vm[{run_id}] in index.json")


main(sys.argv[1])
