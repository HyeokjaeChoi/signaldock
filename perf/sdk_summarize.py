#!/usr/bin/env python3
"""Reconciles and summarizes SDK device measurement runs (issue 24, ADR-0014). Python 3 standard library only.

  python3 perf/sdk_summarize.py perf/sdk-results/api37-burst          writes run.json and summary.md there
  python3 perf/sdk_summarize.py --all perf/sdk-results                writes perf/sdk-results/summary.md from all run.json files

Inputs per run directory (written by scripts/device-measure.sh): meta.json, result-<phase>.json, calls-<phase>.csv,
db-ids.txt (event IDs stored on the server for this run), proxy.jsonl (every batch the SDK sent).
Numbers are measured in this environment. They are not an SLA.
"""
import csv
import json
import sys
from pathlib import Path

from summarize import pct  # nearest-rank percentile, same as the server load summary

RATE_TOLERANCE = 0.95          # actual start rate must reach this share of the target (ADR-0014)
EVENT_BYTES_RANGE = (900, 1150)  # "about 1 KiB" as the SDK counts one event as sent


def jload(p):
    return json.loads(p.read_text()) if p.exists() else None


def kib(n):
    return None if n is None else round(n / 1024, 1)


def analyze(d):
    d = Path(d)
    meta = jload(d / "meta.json")
    sc = meta["scenario"]
    first = {"normal": "normal", "burst": "burst", "offline": "offline_store"}[sc]
    res = jload(d / f"result-{first}.json")
    drain_res = jload(d / "result-offline_drain.json") if sc == "offline" else None
    run = {"scenario": sc, "run_id": meta["run_id"], "avd": meta["avd"], "meta": meta}
    reasons_invalid, reasons_fail = [], []
    if not meta["completed_phases"] or res is None or (sc == "offline" and drain_res is None) or not (d / f"calls-{first}.csv").exists():
        run.update(verdict="invalid", reasons=["an instrumentation phase did not finish (see the script output)"])
        return run
    run.update(device=res["device"], api=res["api"], release=res["release"], abi=res["abi"], debuggable=res["debuggable"])

    rows = list(csv.DictReader((d / f"calls-{first}.csv").open()))
    if not rows:
        run.update(verdict="invalid", reasons=["calls file has no rows"])
        return run
    ok_rows = [r for r in rows if r["result"] == "ok"]
    bad = {}
    for r in rows:
        if r["result"] != "ok":
            bad[r["result"]] = bad.get(r["result"], 0) + 1
    durs = [int(r["dur_us"]) / 1000 for r in ok_rows]
    starts = [int(r["start_us"]) for r in rows]
    late = [(int(r["start_us"]) - int(r["sched_us"])) / 1000 for r in rows]
    n, target = len(rows), res["targetRate"]
    span = (max(starts) - min(starts)) / 1e6
    actual = (n - 1) / span if span > 0 else None
    run["injection"] = {
        "target_rate_per_s": target if target else "back to back", "planned": res["plannedEvents"], "started": n,
        "actual_rate_per_s": round(actual, 2) if actual else None, "span_s": round(span, 2),
        "max_calls_in_flight": res["maxInFlight"],
        "start_lateness_ms": {"p95": round(pct(late, 95), 1), "max": round(max(late), 1)} if target else None,
        "event_bytes": res.get("eventBytes"),
    }
    if n != res["plannedEvents"]:
        reasons_invalid.append(f"started {n} of {res['plannedEvents']} calls")
    if target and (actual is None or actual < RATE_TOLERANCE * target):
        reasons_invalid.append(f"actual rate {actual and round(actual, 2)}/s is below {RATE_TOLERANCE:.0%} of the target {target}/s")
    eb = res.get("eventBytes")
    if eb is None:
        reasons_invalid.append("event size was not measured")
    elif not EVENT_BYTES_RANGE[0] <= eb <= EVENT_BYTES_RANGE[1]:
        reasons_invalid.append(f"event size {eb} bytes is outside {EVENT_BYTES_RANGE}")
    run["track_ms"] = {"p50": round(pct(durs, 50), 2), "p95": round(pct(durs, 95), 2), "max": round(max(durs), 2), "count": len(durs)} if durs else None
    run["errors"] = {"track_not_ok": bad, "sdk_warnings": res["sdkWarnings"], "last_delivery_error": res.get("lastDeliveryError")}
    if bad:
        reasons_fail.append(f"track() not ok: {bad}")

    tracked = {r["event_id"] for r in ok_rows}
    if len(tracked) != len(ok_rows):
        reasons_fail.append(f"{len(ok_rows) - len(tracked)} successful track() calls returned an event ID that an earlier call already returned")
    if not (d / "db-ids.txt").exists():
        run.update(verdict="invalid", reasons=["database reconciliation is unavailable (db-ids.txt is missing)"])
        return run
    db_lines = [x for x in (d / "db-ids.txt").read_text().split() if x]
    db = set(db_lines)
    if not (d / "proxy.jsonl").exists():
        reasons_invalid.append("proxy.jsonl is missing: no record of what the SDK sent")
    proxy, bad_lines = [], 0
    if (d / "proxy.jsonl").exists():
        for x in (d / "proxy.jsonl").read_text().splitlines():
            try:
                proxy.append(json.loads(x))
            except ValueError:
                bad_lines += 1  # the proxy was cut in the middle of a write
    if bad_lines:
        reasons_invalid.append(f"proxy.jsonl has {bad_lines} unreadable lines")
    sent = [i for b in proxy for i in b.get("eventIds", [])]
    ack_status, unreadable_acks = {}, 0
    for b in proxy:
        if not b.get("eventIds"):
            continue
        try:
            for it in json.loads(b["upstreamBody"])["results"]:
                ack_status[it["status"]] = ack_status.get(it["status"], 0) + 1
        except (ValueError, KeyError, TypeError):
            unreadable_acks += 1  # the record still counts for sent-ID reconciliation
    if unreadable_acks:
        reasons_invalid.append(f"{unreadable_acks} proxy records have no readable ACK response")
    run["reconciliation"] = {
        "tracked_ok": len(tracked), "server_rows": len(db_lines), "missing_on_server": len(tracked - db), "not_tracked_on_server": len(db - tracked),
        "duplicate_server_rows": len(db_lines) - len(db),
        "batches_sent": len(proxy), "events_sent": len(sent), "event_ids_sent_more_than_once": len(sent) - len(set(sent)),
        "batches_not_200": sum(1 for b in proxy if b.get("upstreamStatus") != 200), "ack_status_counts": ack_status,
    }
    rc = run["reconciliation"]
    unseen = len(tracked - set(sent))
    if unseen:
        reasons_invalid.append(f"proxy log has no record of {unseen} tracked events")
    for k, msg in (("missing_on_server", "events missing on the server"), ("not_tracked_on_server", "server rows the SDK never reported"),
                   ("duplicate_server_rows", "duplicate server rows")):
        if rc[k]:
            reasons_fail.append(f"{rc[k]} {msg}")

    d_src = drain_res or res
    drain_s = d_src["drainSeconds"] if drain_res else res["drainSeconds"]
    wait = drain_res["retryWaitAtStartSeconds"] if drain_res else None
    run["drain"] = {"seconds": None if drain_s is None else round(drain_s, 2), "queued_after": d_src["queuedAfterDrain"],
                    "queued_at_injection_end": res.get("queuedAtInjectionEnd", res.get("queued"))}
    if drain_res:
        run["drain"].update(persisted_retry_wait_at_start_s=None if wait is None else round(wait, 1),
                            seconds_after_retry_wait=None if drain_s is None or wait is None else round(max(drain_s - wait, 0), 2))
    if drain_s is None or d_src["queuedAfterDrain"]:
        reasons_fail.append("queue did not drain")

    def total(files):
        return None if files is None else sum(files.values())
    if drain_res:
        run["files_bytes"] = {"after_store": res["files"], "after_drain": drain_res["filesAfterDrain"]}
    else:
        run["files_bytes"] = {"at_injection_end": res["filesAtInjectionEnd"], "after_drain": res["filesAfterDrain"]}

    def pss(m): return m["totalPssKb"]
    def heap(m): return m["javaHeapUsedKb"]
    if drain_res:
        run["memory_kb"] = {"store_process": {"pss_after_init_to_after_store": pss(res["memAfterStore"]) - pss(res["memAfterInit"]),
                                              "heap_after_init_to_after_store": heap(res["memAfterStore"]) - heap(res["memAfterInit"])},
                            "drain_process": {"pss_before_init_to_after_drain": pss(drain_res["memAfterDrain"]) - pss(drain_res["memBeforeInit"]),
                                              "heap_before_init_to_after_drain": heap(drain_res["memAfterDrain"]) - heap(drain_res["memBeforeInit"])},
                            "pss_absolute": {"store_after_store": pss(res["memAfterStore"]), "drain_after_drain": pss(drain_res["memAfterDrain"])}}
    else:
        run["memory_kb"] = {"pss_after_init_to_injection_end": pss(res["memAtInjectionEnd"]) - pss(res["memAfterInit"]),
                            "pss_after_init_to_after_drain": pss(res["memAfterDrain"]) - pss(res["memAfterInit"]),
                            "heap_after_init_to_after_drain": heap(res["memAfterDrain"]) - heap(res["memAfterInit"]),
                            "pss_absolute": {"before_init": pss(res["memBeforeInit"]), "after_init": pss(res["memAfterInit"]),
                                             "injection_end": pss(res["memAtInjectionEnd"]), "after_drain": pss(res["memAfterDrain"])}}
    if reasons_invalid:
        run.update(verdict="invalid", reasons=reasons_invalid + reasons_fail)
    elif reasons_fail:
        run.update(verdict="fail", reasons=reasons_fail)
    else:
        run.update(verdict="pass", reasons=[])
    return run


def md_run(r):
    L = [f"### {r['avd']} (API {r.get('api', '?')}) - {r['scenario']}: **{r['verdict']}**", ""]
    if r["reasons"]:
        L += [f"- Reasons: {'; '.join(r['reasons'])}"]
    if "injection" not in r:
        return L + [""]
    i, t, e, c, dr = r["injection"], r["track_ms"], r["errors"], r["reconciliation"], r["drain"]
    L += [
        f"- Device: {r['device']}, Android {r['release']} (API {r['api']}), {r['abi']}. Build: {r['meta']['build']}.",
        f"- Injection: target {i['target_rate_per_s']} events/s, actual {i['actual_rate_per_s']} events/s over {i['span_s']} s ({i['started']} of {i['planned']} calls); "
        f"max calls in flight {i['max_calls_in_flight']}; start lateness {i['start_lateness_ms']}; event size {i['event_bytes']} bytes.",
        f"- track() completion (ms): " + (f"p50 {t['p50']}, p95 {t['p95']}, max {t['max']} (n={t['count']})." if t else "not available (no successful call)."),
        f"- Errors: track() not ok {e['track_not_ok'] or 0}; SDK WARN/ERROR lines {e['sdk_warnings']}; last delivery error {e['last_delivery_error']}.",
        f"- Reconciliation with server DB: tracked ok {c['tracked_ok']}, server rows {c['server_rows']}, missing {c['missing_on_server']}, "
        f"not tracked {c['not_tracked_on_server']}, duplicate rows {c['duplicate_server_rows']}; sent on the wire {c['events_sent']} events in {c['batches_sent']} batches, "
        f"IDs sent more than once {c['event_ids_sent_more_than_once']}, batches not 200: {c['batches_not_200']}, ACK statuses {c['ack_status_counts']}.",
        f"- Queue drain: {dr}.",
        f"- Files (bytes): {r['files_bytes']}.",
        f"- Memory (KiB): {r['memory_kb']}.",
        f"- Host: {r['meta']['host']['model']}, {r['meta']['host']['cpus']} CPUs, load average before {r['meta']['host']['loadavg_before']}, after {r['meta']['host']['loadavg_after']}. "
        f"Run {r['meta']['started_utc']} to {r['meta']['ended_utc']}, git {r['meta']['git_revision']}.", "",
    ]
    return L


def main():
    a = sys.argv[1:]
    if a and a[0] == "--all":
        root = Path(a[1])
        runs = [json.loads(p.read_text()) for p in sorted(root.glob("*/run.json"))]
        L = ["# SDK device measurement summary (issue 24)", "",
             "Measured in this environment (emulators, Compose server on one Mac). Not an SLA. A run that missed the target injection rate is `invalid`, never `pass` (spec section 7). "
             "One run per AVD and scenario. Method and criteria: ADR-0014.", "",
             "| AVD | Scenario | Verdict | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Not ok | Missing / dup rows | Drain s | queue.db + wal + shm bytes (after drain) | PSS delta KiB |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for r in runs:
            if "injection" not in r:
                L.append(f"| {r['avd']} | {r['scenario']} | {r['verdict']} | - | - | - | - | - | - | - | - |")
                continue
            i, t, c = r["injection"], r["track_ms"] or {"p50": "-", "p95": "-", "max": "-"}, r["reconciliation"]
            fb = r["files_bytes"]["after_drain"]
            fb = f"{fb.get('queue.db', 0)} + wal {fb.get('queue.db-wal', 0)} + shm {fb.get('queue.db-shm', 0)}"
            mem = r["memory_kb"]
            pss = mem.get("pss_after_init_to_after_drain", mem.get("store_process", {}).get("pss_after_init_to_after_store"))
            L.append(f"| {r['avd']} | {r['scenario']} | {r['verdict']} | {i['target_rate_per_s']} / {i['actual_rate_per_s']} | {i['max_calls_in_flight']} | "
                     f"{t['p50']} / {t['p95']} / {t['max']} | {sum(r['errors']['track_not_ok'].values())} | {c['missing_on_server']} / {c['duplicate_server_rows']} | "
                     f"{r['drain']['seconds']} | {fb} | {pss} |")
        L += ["", "Offline row: track() numbers are the offline store phase (endpoint unreachable, calls back to back); drain seconds are the restarted process after the persisted retry wait (see the run details).", ""]
        for r in runs:
            L += md_run(r)
        (root / "summary.md").write_text("\n".join(L))
        print(f"wrote {root / 'summary.md'}")
        return
    bad = False
    for d in a:
        r = analyze(d)
        (Path(d) / "run.json").write_text(json.dumps(r, indent=2))
        (Path(d) / "summary.md").write_text("\n".join(md_run(r)))
        print(f"{d}: {r['verdict']} {'; '.join(r['reasons'])}")
        bad = bad or r["verdict"] != "pass"
    sys.exit(1 if bad else 0)  # a fail or invalid run must not look like success to the script


if __name__ == "__main__":
    main()
