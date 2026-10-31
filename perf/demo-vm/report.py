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
    """ADR-0048 Decision 4 with the clarification of 2026-10-31: `invalid` only when k6 itself was at its CPU limit."""
    base = stages[0]["p95_ms"]
    for s in stages:
        k6 = s.get("k6_cpu_max_full_run")
        if k6 is None:
            k6 = s["cpu_pct"]["sd-k6"]["max"]
        k6_limit = k6 >= K6_CPU_MAX
        why = []
        if s["non_ok_share"] > MAX_NON_OK:
            why.append("non-200 share above 1%")
        if s["p95_ms"] > P95_FACTOR * base:
            why.append("p95 above 2x first stage")
        if s["dropped_iterations"] > 0:
            why.append("dropped iterations (50 VU cap reached)")
        if k6_limit:
            why.append("k6 CPU max (full run) at or above 50%")
        # Server hit: non-200 or p95 rule, or dropped iterations while k6 was under its limit.
        s["server_rule_hit"] = (s["non_ok_share"] > MAX_NON_OK or s["p95_ms"] > P95_FACTOR * base
                                or (s["dropped_iterations"] > 0 and not k6_limit))
        s["verdict"] = "invalid" if k6_limit else ("fail" if why else "pass")
        s["reasons"] = why


def conclusion(stages):
    ok = [s for s in stages if s["verdict"] == "pass"]
    bad = [s for s in stages if s["verdict"] != "pass"]
    out = []
    if ok:
        p = ok[-1]
        c = lambda n: f"{p['cpu_pct'][n]['mean']}% mean, {p['cpu_pct'][n]['max']}% max"
        out.append(f"- Highest `pass` rate: {p['rate']} req/s ({p['rate'] * 20} events/s), p95 {p['p95_ms']} ms, p99 {p['p99_ms']} ms, "
                   f"non-200 share {p['non_ok_share'] * 100:.1f}%.")
        out.append(f"- CPU at that stage (percent of one CPU, measure window): db {c('signaldock-db-1')}; server {c('signaldock-server-1')}.")
    else:
        out.append("- No stage has the verdict `pass`.")
    if bad:
        q = bad[0]
        out.append(f"- Lowest rate that is not `pass`: {q['rate']} req/s, verdict `{q['verdict']}`, server rule hit: "
                   f"{'yes' if q['server_rule_hit'] else 'no'}, non-200 share {q['non_ok_share'] * 100:.1f}%.")
    else:
        out.append("- No stage is below `pass`; the limit was not reached.")
    if ok and bad:
        out.append(f"- The capacity of this configuration is between {ok[-1]['rate']} and {bad[0]['rate']} req/s. Rates in between were not run.")
    out.append("- An `invalid` stage is not a server latency number. Its `server rule hit` column still shows whether the server shed load.")
    return "\n".join(out)


def table(stages):
    out = ["| req/s | events/s | requests | ok | other | p50 ms | p95 ms | p99 ms | max ms | dropped | server rule hit | verdict |",
           "|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---|---|"]
    for s in stages:
        other = ", ".join(f"{k} {v}" for k, v in s["outcomes"].items() if k != "ok") or "-"
        out.append(f"| {s['rate']} | {s['rate'] * 20} | {s['measure_requests']} | {s['outcomes'].get('ok', 0)} | {other} | "
                   f"{s['p50_ms']} | {s['p95_ms']} | {s['p99_ms']} | {s['max_ms']} | {s['dropped_iterations']} | {'yes' if s['server_rule_hit'] else 'no'} | {s['verdict']} |")
    out += ["", "CPU in percent of one CPU, mean / max of `docker stats` samples (about 7 s apart) in the measure window only (a sample can exceed the `--cpus` quota). The k6 limit check uses the max of the full run.", "",
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
    verdict_lines = "\n".join(f"- {s['rate']} req/s: **{s['verdict']}**" + (f" ({'; '.join(s['reasons'])})" if s["reasons"] else "") for s in stages)
    body = f"""# Live demo VM capacity ({date})

Run: `{run_id}`. Method and decisions: [ADR-0048](../../../docs/adr/0048-demo-vm-capacity-measurement.md) and [perf/demo-vm/README.md](../../demo-vm/README.md).
This file is written by `python3 perf/demo-vm/report.py perf/results/{run_id}`. Do not edit it by hand.

Target: {VM}. Load: k6 on the same VM, 20 events (about 1 KiB each) per request, 30 s warm-up and 180 s measure per stage, collection API only.
Rules: non-200 share above 1%, p95 above 2x the first stage ({stages[0]['p95_ms']} ms), or dropped iterations above 0 (the 50 VU cap was reached because the server is slow) = a server rule hit.
`pass` = no rule hit. `fail` = a server rule hit and k6 was under its limit. `invalid` = k6 CPU max over the full run was at or above 50%, so the stage is not a server latency number. `server rule hit` is shown for every stage, also for `invalid` ones.

## Stages

{table(stages)}

## Verdict per stage

{verdict_lines}

## Conclusion

{conclusion(stages)}

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
