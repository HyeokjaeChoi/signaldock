#!/usr/bin/env python3
"""ONE overload run of issue 23 (ADR-0013, Q133): saturate the server on purpose and check that it sheds load cleanly.

  python3 perf/overload.py --run-id overload-1

Same stack, seed, and server restart as perf/run_one.py, but two server limits are lowered for this run only
(SIGNALDOCK_DB_CONCURRENCY and SIGNALDOCK_DB_ADMISSION_MS via compose variables; product defaults do not change).
Phases on one k6 clock: baseline, overload, recovery. The server is NOT restarted between phases.
Checks (set before the run): only 200 and 503 answers, every 503 has a positive integer Retry-After, every ACKed event is stored
exactly once, no event of a 503 request is stored, and the last 30 s of recovery are all 200 with latency near the baseline.
Writes perf/results/<run-id>/ with kind=overload in manifest.json and summary.json. Never uses k6 cloud.
"""
import argparse
import csv
import datetime as dt
import gzip
import json
import os
import shutil
import subprocess
import sys
import time
from collections import Counter, defaultdict

import common as c
import run_one as ro
import seed as seedmod

PARAMS = {"seed_size": 100000, "dist": "recent70", "window_days": 30, "pool_size": 4, "concurrency": 2, "admission_ms": 100,
          "base_s": 30, "over_s": 30, "rec_s": 60, "rate": 5, "over_rate": 200, "heavy_rate": 150}
WINDOW_S = 5  # time-series bucket


def classify(rows):
    return Counter((r["ep"], r["phase"], r["status"]) for r in rows)


def phase_stats(rows):
    """Per phase and endpoint: request count, status and outcome counts, Retry-After and error-code counts, latency (all and 200 only)."""
    out = {}
    for ph in ("baseline", "overload", "recovery"):
        out[ph] = {}
        for ep in ("collect", "probe", "heavy"):
            sel = [r for r in rows if r["ep"] == ep and r["phase"] == ph]
            if not sel:
                continue
            d = sorted(float(r["duration_ms"]) for r in sel)
            ok = sorted(float(r["duration_ms"]) for r in sel if r["status"] == "200")
            out[ph][ep] = {"requests": len(sel), "status": dict(Counter(r["status"] for r in sel)),
                           "outcomes": dict(Counter(r["outcome"] for r in sel)),
                           "retry_after": dict(Counter(r["retry_after"] for r in sel if r["status"] != "200")),
                           "error_codes": dict(Counter(r["code"] for r in sel if r["status"] != "200")),
                           "p50_ms": c.percentile(d, 50), "p95_ms": c.percentile(d, 95), "max_ms": d[-1],
                           "p95_ok_ms": c.percentile(ok, 95), "max_ok_ms": ok[-1] if ok else None}
    return out


def series(rows, total_s):
    """Counts per 5 s of request start time, for the report: [t, ep, n, s200, s503, other, p95_ms]."""
    out = []
    for ep in ("collect", "probe", "heavy"):
        for w in range(total_s // WINDOW_S):
            sel = [r for r in rows if r["ep"] == ep and w * WINDOW_S * 1000 <= int(r["at_ms"]) < (w + 1) * WINDOW_S * 1000]
            if sel:
                n503 = sum(r["status"] == "503" for r in sel)
                n200 = sum(r["status"] == "200" for r in sel)
                out.append([w * WINDOW_S, ep, len(sel), n200, n503, len(sel) - n200 - n503,
                            c.percentile(sorted(float(r["duration_ms"]) for r in sel), 95)])
    return out


def reconcile(salt, rows):
    """Rows stored per collection request (by event ID) against what the client saw."""
    outcome = {int(r["id"]): r["outcome"] for r in rows if r["ep"] == "collect"}
    stored = Counter()
    for line in c.psql(f"SELECT (('x'||right(event_id::text,12))::bit(48)::bigint) FROM events WHERE event_id::text LIKE '{salt}-%'").splitlines():
        stored[int(line) // c.EVENTS_PER_BATCH] += 1
    by = lambda o: [s for s, x in outcome.items() if x == o]
    full = lambda s: stored[s] == c.EVENTS_PER_BATCH
    other = [s for s, x in outcome.items() if x not in ("ok", "shed")]
    return {"requests": len(outcome), "rows_for_run_ids": sum(stored.values()),
            "ok_requests": len(by("ok")), "ok_requests_not_fully_stored": sum(not full(s) for s in by("ok")),
            "shed_requests": len(by("shed")), "rows_stored_for_shed_requests": sum(stored[s] for s in by("shed")),
            "other_requests": len(other), "rows_stored_for_other_requests": sum(stored[s] for s in other),
            "rows_for_ids_without_result": sum(n for s, n in stored.items() if s not in outcome),
            "duplicate_ids_in_db": int(c.psql(f"SELECT count(*) - count(DISTINCT event_id) FROM events WHERE event_id::text LIKE '{salt}-%'"))}


def read_raw(path):
    rows, counters = [], defaultdict(float)
    with open(path) as f:
        for line in f:
            if '"type":"Point"' not in line:
                continue
            if '"metric":"req_ms"' in line:
                d = json.loads(line)
                t = d["data"]["tags"]
                rows.append({"time": d["data"]["time"], "ep": t["ep"], "phase": t["phase"], "outcome": t["outcome"], "status": t["status"],
                             "id": t["id"], "at_ms": int(t["at"]), "duration_ms": d["data"]["value"], "retry_after": t["retry_after"], "code": t["code"]})
            elif '"metric":"bodies_exhausted"' in line:
                counters["bodies_exhausted"] += json.loads(line)["data"]["value"]
    return sorted(rows, key=lambda r: r["time"]), counters


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    out = c.Path(a.out) if a.out else c.RESULTS / a.run_id
    try:
        execute(a, out)
    except BaseException:
        import traceback
        if out.exists():
            (out / "ERROR.txt").write_text(traceback.format_exc())
        c.compose("rm", "-sf", "server", check=False)
        raise


def execute(a, out):
    P = PARAMS
    if out.exists():
        sys.exit(f"{out} exists; run ids are never reused")
    out.mkdir(parents=True)
    env = c.ensure_env()
    meta = json.loads(seedmod.meta_path(P["seed_size"], P["dist"]).read_text())
    T = dt.datetime.fromisoformat(meta["reference_time_T"])
    frm, to = c.iso_ms(T - dt.timedelta(days=P["window_days"])), c.iso_ms(T)
    salt = c.run_salt(a.run_id)
    total_s = P["base_s"] + P["over_s"] + P["rec_s"]
    times = {"run_start": ro.stamp()}
    manifest = {"kind": "overload", "run_id": a.run_id, "status": "started", "git": c.git_state(), "decision": "Q133 / ADR-0013",
                "parameters": P, "reference_time_T": meta["reference_time_T"], "window": {"from": frm, "to": to}, "seed": meta,
                "server_limits": {**ro.BENCH_PROFILE, "concurrency_limit": P["concurrency"], "admission_ms": P["admission_ms"]},
                "times": times, "credentials": "not recorded (random per-machine test keys in perf/work/env)"}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    seed_db = seedmod.seed_name(P["seed_size"], P["dist"])
    c.log(f"[{a.run_id}] restore {seed_db} -> bench")
    c.compose("up", "-d", "--wait", "db")
    c.wait_db()
    c.compose("rm", "-sf", "server")
    c.psql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='bench'", db="postgres")
    c.psql("DROP DATABASE IF EXISTS bench", db="postgres")
    c.psql(f"CREATE DATABASE bench TEMPLATE {seed_db}", db="postgres")
    c.psql("ANALYZE")
    c.psql("CHECKPOINT")
    assert int(c.psql("SELECT count(*) FROM events")) == P["seed_size"]
    exp_total = int(c.psql(f"SELECT count(*) FROM events WHERE received_at >= '{frm}' AND received_at < '{to}'"))
    scheduled = P["rate"] * (total_s - P["over_s"]) + P["over_rate"] * P["over_s"]
    bodies = c.WORK / "bodies" / f"{a.run_id}.jsonl"
    bodies.parent.mkdir(parents=True, exist_ok=True)
    ro.gen_bodies(salt, int(scheduled * 1.1) + 200, bodies)
    manifest["load_fixture_sha256"] = c.sha256_file(bodies)

    c.compose("up", "-d", "--no-deps", "server", env={"PERF_POOL_SIZE": str(P["pool_size"]), "PERF_CONCURRENCY": str(P["concurrency"]),
                                                      "PERF_ADMISSION_MS": str(P["admission_ms"])})
    ro.wait_health()
    end = time.time() + 60
    pool_ready = False
    while time.time() < end and not pool_ready:
        pool_ready = int(c.psql("SELECT count(*) FROM pg_stat_activity WHERE datname='bench' AND application_name NOT LIKE 'psql%'")) >= P["pool_size"]
        if not pool_ready:
            time.sleep(1)
    manifest["pool_ready"] = pool_ready
    started_at = ro.sh("docker", "inspect", "-f", "{{.State.StartedAt}} {{.RestartCount}}", c.SERVER_CONTAINER)
    times["server_ready"] = ro.stamp()
    manifest["environment"] = ro.environment_facts()
    manifest["postgres_settings"] = ro.pg_settings()
    manifest["server_effective_env"] = {k: v for k, v in (l.split("=", 1) for l in ro.sh(
        "docker", "exec", c.SERVER_CONTAINER, "env").splitlines() if l.startswith("SIGNALDOCK_DB_") and "PASSWORD" not in l and "_USER" not in l)}
    cg0 = {"db": ro.cgroup(c.DB_CONTAINER), "server": ro.cgroup(c.SERVER_CONTAINER)}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str) + "\n")

    k6env = {**os.environ, "BASE_S": str(P["base_s"]), "OVER_S": str(P["over_s"]), "REC_S": str(P["rec_s"]), "RATE": str(P["rate"]),
             "OVER_RATE": str(P["over_rate"]), "HEAVY_RATE": str(P["heavy_rate"]), "BASE_URL": ro.SERVER_URL, "SALT": salt, "BODIES": str(bodies),
             "FROM": frm, "TO": to, "COLLECTION_KEY": env["SIGNALDOCK_COLLECTION_KEY"], "QUERY_KEY": env["SIGNALDOCK_QUERY_KEY"]}
    raw = out / "k6-raw.json"
    c.log(f"k6: baseline {P['base_s']}s, overload {P['over_s']}s, recovery {P['rec_s']}s")
    times["k6_start"] = ro.stamp()
    k6 = subprocess.Popen(["k6", "run", "--quiet", "--no-usage-report", "--out", f"json={raw}", "--summary-export", str(out / "k6-summary.json"),
                           str(c.PERF / "overload.js")], env=k6env, stdout=open(out / "k6.log", "w"), stderr=subprocess.STDOUT)
    sampler = ro.Sampler(k6.pid)
    sampler.start()
    k6_exit = None
    try:
        k6_exit = k6.wait(timeout=total_s + 120)
    finally:
        times["k6_end"] = ro.stamp()
        sampler.stop.set()
        if k6.poll() is None:
            k6.kill()
    drained = False
    t_drain = time.time()
    while time.time() - t_drain < 30:
        if int(c.psql("SELECT count(*) FROM pg_stat_activity WHERE datname='bench' AND state <> 'idle' AND application_name NOT LIKE 'psql%' "
                      "AND pid <> pg_backend_pid()")) == 0:
            drained = True
            break
        time.sleep(0.5)
    sampler.join(timeout=20)
    cg1 = {"db": ro.cgroup(c.DB_CONTAINER), "server": ro.cgroup(c.SERVER_CONTAINER)}

    rows, counters = read_raw(raw)
    if not any(r["ep"] == "collect" for r in rows):
        raise RuntimeError("k6 recorded no collection requests (startup failure or wrong BASE_URL); see k6.log. The run directory is kept with ERROR.txt")
    k6s = json.loads((out / "k6-summary.json").read_text())
    dropped = k6s["metrics"].get("dropped_iterations", {})
    dropped = dropped.get("count", dropped.get("values", {}).get("count", 0))
    # Wall clock of the k6 time zero: the median of (response time - start offset - duration) over collection requests.
    zero = sorted((ro.parse_time(r["time"]) - dt.timedelta(milliseconds=r["at_ms"] + r["duration_ms"])).timestamp() for r in rows if r["ep"] == "collect")
    times["test_start_wall"] = ro.stamp(dt.datetime.fromtimestamp(zero[len(zero) // 2], dt.timezone.utc))
    with gzip.open(str(raw) + ".gz", "wt") as g, open(raw) as f:
        shutil.copyfileobj(f, g)
    raw.unlink()
    with open(out / "requests.csv", "w", newline="") as f:
        w = csv.DictWriter(f, list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    keys = sorted({k for s in sampler.rows for k in s if k != "pg_conns"})
    with open(out / "samples.csv", "w", newline="") as f:
        w = csv.DictWriter(f, keys + ["pg_conns"])
        w.writeheader()
        w.writerows({**s, "pg_conns": json.dumps(s.get("pg_conns", {}))} for s in sampler.rows)
    with open(out / "server.log", "w") as f:
        subprocess.run(["docker", "logs", c.SERVER_CONTAINER], stdout=f, stderr=subprocess.STDOUT, check=False)
    (out / "gc.log").write_text(c.run(["docker", "exec", c.SERVER_CONTAINER, "cat", "/tmp/gc.log"], check=False).stdout)
    ended_at = ro.sh("docker", "inspect", "-f", "{{.State.StartedAt}} {{.RestartCount}}", c.SERVER_CONTAINER)
    rec = reconcile(salt, rows)
    check = ro.aggregate_check(frm, to, env["SIGNALDOCK_QUERY_KEY"], P["window_days"])
    seed_rows_after = int(c.psql(f"SELECT count(*) FROM events WHERE event_id::text NOT LIKE '{salt}-%'"))
    c.compose("rm", "-sf", "server")
    c.psql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='bench'", db="postgres")
    bodies.unlink(missing_ok=True)
    times["run_end"] = ro.stamp()

    ph = phase_stats(rows)
    over_end = (P["base_s"] + P["over_s"]) * 1000
    last = [r for r in rows if r["ep"] in ("collect", "probe") and (total_s - 30) * 1000 <= int(r["at_ms"]) < total_s * 1000]
    last_collect = sorted(float(r["duration_ms"]) for r in last if r["ep"] == "collect")
    base = ph.get("baseline", {})
    base_p95 = base.get("collect", {}).get("p95_ms")
    limit = None if base_p95 is None else max(2 * base_p95, base_p95 + 20)  # same noise rule as summarize.py: 2x or 20 ms, whichever is larger
    ser = series(rows, total_s)
    clean = [s for s in ser if s[1] == "collect" and s[0] * 1000 >= over_end and s[5] == 0 and s[4] == 0]
    first_clean = min((s[0] for s in clean), default=None)
    bad, invalid = [], []
    if k6_exit != 0:
        invalid.append(f"k6 exit code {k6_exit}; phases may be incomplete (see k6.log)")
    if not pool_ready:
        invalid.append("pool not ready before the baseline phase")
    if counters.get("bodies_exhausted"):
        invalid.append("request fixtures exhausted")
    if dropped:
        invalid.append(f"dropped_iterations={int(dropped)} (generator limit, not server behavior)")
    if not drained:
        invalid.append("DB work still active after 30 s drain")
    missing = [ep for ep in ("collect", "probe") if ep not in base]
    if missing:
        invalid.append(f"no baseline requests recorded for {missing}")
    if not ph.get("overload", {}).get("collect", {}).get("status", {}).get("503"):
        invalid.append("no 503 on collection requests in the overload phase: the server was not saturated")
    stray = {(r["ep"], r["phase"], r["outcome"]) for r in rows if r["outcome"] not in ("ok", "shed")}
    if stray:
        bad.append(f"answers other than 200 or 503 with valid Retry-After: {sorted(stray)}")
    for ep in ("collect", "probe"):
        if ep in base and base[ep]["status"] != {"200": base[ep]["requests"]}:
            bad.append(f"baseline {ep} not all 200: {base[ep]['status']}")
    if rec["ok_requests_not_fully_stored"] or rec["duplicate_ids_in_db"] or rec["rows_for_ids_without_result"]:
        bad.append(f"ACK/storage mismatch: {rec}")
    if rec["rows_stored_for_shed_requests"] or rec["rows_stored_for_other_requests"]:
        bad.append(f"rows stored for rejected requests: {rec}")
    if any(r["status"] != "200" for r in last):
        bad.append("last 30 s of recovery contain non-200 answers")
    if not last_collect or limit is None or c.percentile(last_collect, 95) > limit:
        bad.append(f"recovery collect p95 {c.percentile(last_collect, 95)} ms > {limit} ms (2x baseline or +20 ms)")
    if started_at != ended_at:
        bad.append(f"server restarted or its state changed: {started_at} -> {ended_at}")
    if seed_rows_after != P["seed_size"] or not (check["names_ok"] and check["days_ok"] and check["list_first_page_ok"]):
        bad.append(f"seed rows or aggregates changed: rows={seed_rows_after}, check={check}")
    verdict = "invalid" if invalid else "fail" if bad else "pass"
    summary = {
        "kind": "overload", "run_id": a.run_id, "verdict": verdict, "invalid_reasons": invalid, "fail_reasons": bad, "parameters": P,
        "phases": ph, "series_5s": ser, "dropped_iterations": dropped, "reconciliation": rec, "aggregate_check": check,
        "seed_rows_after_run": seed_rows_after,
        "recovery": {"first_clean_5s_window_after_overload_s": None if first_clean is None else first_clean - over_end // 1000,
                     "last_30s_collect_p95_ms": c.percentile(last_collect, 95), "baseline_collect_p95_ms": base_p95, "limit_ms": limit,
                     "last_30s_requests": len(last), "last_30s_non_200": sum(r["status"] != "200" for r in last)},
        "server_state": {"before": started_at, "after": ended_at, "note": "StartedAt and RestartCount of the server container"},
        "cgroup_deltas": {k: ro.cg_delta(cg0[k], cg1[k]) for k in cg0},
        "gc": ro.parse_gc(out / "gc.log"),
    }
    manifest["status"] = "completed"
    (out / "summary.json").write_text(json.dumps(summary, indent=2, default=str) + "\n")
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str) + "\n")
    c.log(f"[{a.run_id}] verdict={verdict} invalid={invalid} fail={bad}")
    print(json.dumps({"run_id": a.run_id, "verdict": verdict, "out": str(out)}))


if __name__ == "__main__":
    main()
