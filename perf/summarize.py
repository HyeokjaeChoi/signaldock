#!/usr/bin/env python3
"""Writes a Markdown summary, a latency CSV, and (with --index) the JSON for perf/report/ from run directories of run_one.py.

  python3 perf/summarize.py perf/results/pilot-2 [more run dirs] --out perf/results/pilot-2/report.md
  python3 perf/summarize.py perf/results/*/ --out perf/results/report.md --index perf/results/index.json

Per-run values stay separate: percentiles of different runs are never averaged into one p95 (performance-plan.md,
acceptance 5). The graphical report is the static viewer in perf/report/.
"""
import argparse
import csv
import datetime as dt
import json
import re
from pathlib import Path

TARGET = {"collect": 500, "list": 500, "by-name": 1000, "by-time": 1000}


def load(d):
    d = Path(d)
    return d, json.loads((d / "manifest.json").read_text()), json.loads((d / "summary.json").read_text())


def pct(vals, p):
    vals = sorted(vals)
    import math
    return vals[max(1, math.ceil(p / 100 * len(vals))) - 1] if vals else None


def per_minute(d, m):
    """p50/p95 per 60 s of the measurement phase (request start time). Few samples per minute: a trend check, not a tail estimate."""
    warm = m["load"]["warmup_s"] * 1000
    rows = [r for r in csv.DictReader(open(d / "requests.csv")) if r["phase"] == "measure"]
    table, spread = {}, {}
    for ep in TARGET:
        buckets = {}
        for r in rows:
            if r["ep"] == ep:
                buckets.setdefault((int(r["at_ms"]) - warm) // 60000, []).append(float(r["duration_ms"]))
        table[ep] = {k: (len(v), pct(v, 50), pct(v, 95)) for k, v in sorted(buckets.items())}
        p95s = [x[2] for x in table[ep].values()]
        # Heuristic (not from the plan): unstable when per-minute p95 varies more than 2x, or leaves 20 ms noise floor behind.
        spread[ep] = bool(p95s) and max(p95s) > max(2 * min(p95s), min(p95s) + 20)
    return table, spread


def fmt(v):
    return "n/a" if v is None else f"{v:.1f}" if isinstance(v, float) else str(v)


def latency_csv(runs, out_dir):
    """Raw latency points for any plotting tool (the graphical report is perf/report/, which reads index.json)."""
    with open(out_dir / "figure-data-latency.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["run_id", "ep", "phase", "elapsed_s", "duration_ms"])
        for d, m, s in runs:
            for r in csv.DictReader(open(d / "requests.csv")):
                w.writerow([m["run_id"], r["ep"], r["phase"], int(r["at_ms"]) / 1000, r["duration_ms"]])


def parse_ts(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def pool_series(d, m):
    """Hikari active/idle/pending per second since test start, from server.log (gitignored; empty when it is missing)."""
    log = d / "server.log"
    if not log.exists():
        return []
    start = parse_ts(m["times"]["test_start_wall"])
    base = start.hour * 3600 + start.minute * 60 + start.second + start.microsecond / 1e6
    out = []
    for line in open(log, errors="replace"):
        x = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d{3}) .*pool active=(\d+) idle=(\d+) pending=(\d+)", line)
        if x:
            sec = int(x[1]) * 3600 + int(x[2]) * 60 + int(x[3]) + int(x[4]) / 1000
            delta = (sec - base + 43200) % 86400 - 43200  # log lines carry time of day only
            out.append([round(delta, 1), int(x[5]), int(x[6]), int(x[7])])
    return out


def container_series(d, m):
    start = parse_ts(m["times"]["test_start_wall"])
    keep = ("db_cpu_pct", "server_cpu_pct", "db_mem_mib", "server_mem_mib")
    out = []
    for r in csv.DictReader(open(d / "samples.csv")):
        out.append({"t": round((parse_ts(r["ts"]) - start).total_seconds(), 1), **{k: float(r[k]) for k in keep if r.get(k)}})
    return out


def statements(d, top=20):
    f = d / "statements.csv"
    if not f.exists():
        return None
    rows = list(csv.DictReader(open(f)))
    app = [r for r in rows if r["harness"] != "True"]
    keep = lambda r: {"calls": int(r["calls"]), "rows": int(r["rows"]), "total_exec_ms": float(r["total_exec_time"]),
                      "mean_exec_ms": float(r["mean_exec_time"]), "max_exec_ms": float(r["max_exec_time"]), "query": r["query"]}
    return {"statements": len(rows), "harness_statements": len(rows) - len(app), "top_by_total_time": [keep(r) for r in app[:top]]}


def index_entry(d, m, s):
    non_ok = {ep: {k: v for k, v in x["outcomes"].items() if k != "ok"} for ep, x in s["measurement"].items()}
    v = s["load_validity"]
    return {
        "run_id": m["run_id"], "counted": m["counts_as_measured_run"], "verdict": s["verdict"],
        "invalid_reasons": s["invalid_reasons"], "fail_reasons": s["fail_reasons"],
        "repetition": m["repetition"], "pool_order": m["pool_order"], "pool_size": m["pool_size"], "combination": m["combination"],
        "git": {"revision": m["git"]["revision"][:12], "dirty": m["git"]["dirty"]},
        "warmup_s": m["load"]["warmup_s"], "measure_s": m["load"]["measure_s"],
        "endpoints": {ep: {"target_p95_ms": TARGET[ep], **{k: x[k] for k in ("samples", "p50_ms", "p95_ms", "max_ms", "outcomes")}}
                      for ep, x in s["measurement"].items()},
        "injection": {"scheduled": v["scheduled_measurement_iterations"], "started": v["started_measurement_iterations"],
                      "dropped": v["dropped_iterations"]},
        "non_ok_outcomes": {ep: o for ep, o in non_ok.items() if o},
        "skipped_ticks": {ep: t["measure_skipped"] for ep, t in s["dashboard_ticks"].items()},
        "hikari_series": pool_series(d, m), "container_series": container_series(d, m),
        "cgroup": {k: {n: s["cgroup_deltas"][k].get(f"cpu.stat:{n}") for n in ("nr_periods", "nr_throttled", "throttled_usec")}
                   for k in ("server", "db")},
        "statements": statements(d),
    }


def overload_entry(d, m, s):
    """One overload run (perf/overload.py, Q133): per-phase status counts, Retry-After values, 5 s series, recovery, storage checks."""
    keep = ("requests", "status", "retry_after", "error_codes", "p50_ms", "p95_ms", "p95_ok_ms", "max_ms")
    return {"run_id": m["run_id"], "verdict": s["verdict"], "invalid_reasons": s["invalid_reasons"], "fail_reasons": s["fail_reasons"],
            "git": {"revision": m["git"]["revision"][:12], "dirty": m["git"]["dirty"]}, "parameters": s["parameters"],
            "server_effective_env": m.get("server_effective_env", {}),
            "phases": {ph: {ep: {k: x[k] for k in keep} for ep, x in eps.items()} for ph, eps in s["phases"].items()},
            "series_5s": s["series_5s"], "recovery": s["recovery"], "reconciliation": s["reconciliation"],
            "dropped_iterations": s["dropped_iterations"], "hikari_series": pool_series(d, m), "container_series": container_series(d, m)}


def overload_markdown(d, m, s):
    P = s["parameters"]
    L = [f"## {m['run_id']} (overload run, Q133)", "", f"- Verdict: **{s['verdict']}**",
         f"- Git: `{m['git']['revision'][:12]}`, dirty={m['git']['dirty']}, diff sha256 `{m['git']['dirty_diff_sha256'][:16]}`",
         f"- Server limits for this run: concurrency {P['concurrency']}, admission wait {P['admission_ms']} ms, pool {P['pool_size']}; seed {P['seed_size']} rows, {P['dist']}, window {P['window_days']} d",
         f"- Phases: baseline {P['base_s']} s, overload {P['over_s']} s, recovery {P['rec_s']} s. Collection {P['rate']} rps (overload phase: {P['over_rate']} rps); "
         f"probe 1 rps (list); heavy {P['heavy_rate']} rps by-name in the overload phase only. Dropped iterations: {int(s['dropped_iterations'])}"]
    for k in ("invalid_reasons", "fail_reasons"):
        for r in s[k]:
            L.append(f"- {k.split('_')[0]}: {r}")
    L += ["", "| Phase | Endpoint | requests | status counts | Retry-After values | error codes | p50 ms | p95 ms | p95 of 200 ms | max ms |", "|---|---|---|---|---|---|---|---|---|---|"]
    for ph, eps in s["phases"].items():
        for ep, x in eps.items():
            L.append(f"| {ph} | {ep} | {x['requests']} | {x['status']} | {x['retry_after'] or '-'} | {x['error_codes'] or '-'} | {fmt(x['p50_ms'])} | {fmt(x['p95_ms'])} | {fmt(x['p95_ok_ms'])} | {fmt(x['max_ms'])} |")
    r = s["recovery"]
    L += ["", f"- Recovery: first all-200 5 s window of collection starts {r['first_clean_5s_window_after_overload_s']} s after the overload phase ended; last 30 s: "
          f"{r['last_30s_requests']} collection/probe requests, {r['last_30s_non_200']} not 200, collection p95 {fmt(r['last_30s_collect_p95_ms'])} ms "
          f"(baseline {fmt(r['baseline_collect_p95_ms'])} ms, limit {fmt(r['limit_ms'])} ms). Server state before/after: {s['server_state']['before']} / {s['server_state']['after']}",
          f"- Storage reconciliation: {s['reconciliation']}", f"- Aggregate check after the run: {s['aggregate_check']}; seed rows after run: {s['seed_rows_after_run']}", ""]
    return L


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--index", help="also write one JSON file with all given runs for perf/report/ (per-run values only)")
    a = ap.parse_args()
    runs = [load(r) for r in a.runs]
    over = [r for r in runs if r[1].get("kind") == "overload"]
    runs = [r for r in runs if r[1].get("kind") != "overload"]
    out = Path(a.out)
    latency_csv(runs + over, out.parent)
    if a.index:
        runs.sort(key=lambda r: (not r[1]["pilot"], r[1]["run_id"]))  # pilots first, then by id
        Path(a.index).write_text(json.dumps({"targets_p95_ms": TARGET, "runs": [index_entry(*r) for r in runs],
                                       "overload": [overload_entry(*r) for r in over]}, separators=(",", ":")) + "\n")
    L = ["# Server load measurement summary", "",
         "Source: `perf/summarize.py`. Values come from `summary.json` and `requests.csv` of each run. "
         "Targets are project targets set before measurement, not an SLA. Latency is HTTP start to completion as seen by k6 "
         "(sending + waiting + receiving); client connection wait is not included; it is stored in each `summary.json` as `client_blocked_connecting_max_ms`.", ""]
    for d, m, s in runs:
        c = m["combination"]
        L += [f"## {m['run_id']}", "",
              f"- Verdict: **{s['verdict']}**" + ("" if m["counts_as_measured_run"] else " (pilot: does not count as a measured run)"),
              f"- Combination: seed {c['seed_size']} rows, {c['distribution']}, window {c['window_days']} d, {c['clients']} Dashboard clients, pool {m['pool_size']}",
              f"- Git: `{m['git']['revision'][:12]}`, dirty={m['git']['dirty']}, diff sha256 `{m['git']['dirty_diff_sha256'][:16]}`",
              f"- T: {m['reference_time_T']}; window {m['window']['from']} .. {m['window']['to']}; seed csv sha256 `{m['seed']['csv_sha256'][:16]}`",
              f"- Warm-up {m['load']['warmup_s']} s, measurement {m['load']['measure_s']} s; times: {m['times']['test_start_wall']} to {m['times']['measurement_end_wall']}"]
        for k in ("invalid_reasons", "fail_reasons"):
            for r in s[k]:
                L.append(f"- {k.split('_')[0]}: {r}")
        L += ["", "| Endpoint | samples | p50 ms | p95 ms | max ms | target p95 | outcomes |", "|---|---|---|---|---|---|---|"]
        for ep, t in TARGET.items():
            x = s["measurement"][ep]
            L.append(f"| {ep} | {x['samples']} | {fmt(x['p50_ms'])} | {fmt(x['p95_ms'])} | {fmt(x['max_ms'])} | {t} | {x['outcomes']} |")
        table, spread = per_minute(d, m)
        L += ["", "Per-minute trend in the measurement phase (n / p50 ms / p95 ms):", "", "| Endpoint | " + " | ".join(
            f"min {k + 1}" for k in sorted({k for t in table.values() for k in t})) + " | variable? |", "|---|" + "---|" * (len(
                {k for t in table.values() for k in t}) + 1)]
        for ep, t in table.items():
            L.append(f"| {ep} | " + " | ".join(f"{n} / {fmt(a)} / {fmt(b)}" for n, a, b in t.values()) + f" | {'yes' if spread[ep] else 'no'} |")
        v = s["load_validity"]
        L += ["", f"- Load validity: started {v['started_measurement_iterations']} of {v['scheduled_measurement_iterations']} scheduled "
                  f"collection iterations, dropped_iterations={v['dropped_iterations']}",
              f"- Dashboard ticks (measurement): {json.dumps(s['dashboard_ticks'])}",
              f"- ACKs: {s['acks']}", f"- Reconciliation: {s['reconciliation']}", f"- Aggregate check: {s['aggregate_check']}",
              f"- Rows added: {s['db']['rows_added_total']} (about {s['db']['rows_added_by_end_of_warmup_approx']} by end of warm-up); "
              f"WAL {s['db']['wal_bytes_run']} bytes; events table {s['db']['final']['events_table_bytes']} bytes, index {s['db']['final']['events_index_bytes']} bytes",
              f"- Server stages in measurement: {json.dumps(s['server_stage_timings'].get('measure', {}))}",
              f"- Hikari counters in measurement (1 s samples): {s['hikari_pool_counters'].get('measure')}",
              f"- Containers/generator in measurement: {json.dumps(s['containers_measure'])}",
              f"- GC: {s['gc']}; cgroup throttling server: nr_throttled={s['cgroup_deltas']['server'].get('cpu.stat:nr_throttled')}, "
              f"throttled_usec={s['cgroup_deltas']['server'].get('cpu.stat:throttled_usec')}; db: nr_throttled={s['cgroup_deltas']['db'].get('cpu.stat:nr_throttled')}",
              f"- Not collected: {'; '.join(s['metrics_not_collected'])}", ""]
    for r in over:
        L += overload_markdown(*r)
    L += ["## Figures", "", "`figure-data-latency.csv` holds every request (run_id, ep, phase, elapsed_s, duration_ms) for plotting. "
          "Graphs: `perf/report/` (static viewer of `perf/results/index.json`).", ""]
    out.write_text("\n".join(L))


if __name__ == "__main__":
    main()
