#!/usr/bin/env python3
"""Writes a Markdown summary (and figures when matplotlib is installed) for run directories from run_one.py.

  python3 perf/summarize.py perf/results/pilot-1 [more run dirs] --out perf/results/pilot-1/report.md

Per-run values stay separate: percentiles of different runs are never averaged into one p95 (performance-plan.md,
acceptance 5). Without matplotlib the script writes CSV files for later plotting and says so in the report.
"""
import argparse
import csv
import json
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


def figures(runs, out_dir):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        # Fallback: raw points for any plotting tool.
        with open(out_dir / "figure-data-latency.csv", "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["run_id", "ep", "phase", "elapsed_s", "duration_ms"])
            for d, m, s in runs:
                for r in csv.DictReader(open(d / "requests.csv")):
                    w.writerow([m["run_id"], r["ep"], r["phase"], int(r["at_ms"]) / 1000, r["duration_ms"]])
        return None, "matplotlib is not installed here; wrote figure-data-latency.csv (run_id, ep, phase, elapsed_s, duration_ms) for later plotting."
    names = []
    for d, m, s in runs:
        fig, axes = plt.subplots(2, 2, figsize=(10, 6), sharex=True)
        rows = list(csv.DictReader(open(d / "requests.csv")))
        for ax, ep in zip(axes.flat, TARGET):
            pts = [(int(r["at_ms"]) / 1000, float(r["duration_ms"])) for r in rows if r["ep"] == ep]
            ax.scatter(*zip(*pts), s=6) if pts else None
            ax.axhline(TARGET[ep], color="red", lw=0.8, ls="--")
            ax.axvline(m["load"]["warmup_s"], color="gray", lw=0.8)
            ax.set_title(f"{ep} (target p95 {TARGET[ep]} ms)")
            ax.set_ylabel("ms")
        fig.suptitle(m["run_id"] + ": latency over time (gray line: end of warm-up)")
        fig.tight_layout()
        p = out_dir / f"{m['run_id']}-latency.png"
        fig.savefig(p, dpi=110)
        plt.close(fig)
        names.append(p.name)
    return names, None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    runs = [load(r) for r in a.runs]
    out = Path(a.out)
    figs, note = figures(runs, out.parent)
    L = ["# Server load measurement summary", "",
         "Source: `perf/summarize.py`. Values come from `summary.json` and `requests.csv` of each run. "
         "Targets are project targets set before measurement, not an SLA. Latency is HTTP start to completion as seen by k6 "
         "(sending + waiting + receiving); client connection wait is not included and is reported as `blocked+connecting max`.", ""]
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
    if figs:
        L += ["## Figures", ""] + [f"![{n}]({n})" for n in figs] + [""]
    if note:
        L += ["## Figures", "", note, ""]
    out.write_text("\n".join(L))


if __name__ == "__main__":
    main()
