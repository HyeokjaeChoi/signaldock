#!/usr/bin/env python3
"""Executes ONE measurement run of performance-plan.md and writes perf/results/<run-id>/.

  python3 perf/run_one.py --seed-size 100000 --dist recent70 --window 30 --clients 5 --pool 4 \\
      --run-id pilot-1 --pilot

Steps: restore the run database from the seed template, ANALYZE, start the server with the pool size, wait for the
pool, warm-up and measurement under one k6 process, drain, DB checks, ID reconciliation, manifest, raw results,
verdict. Never uses k6 cloud. A run that misses the target injection rate is `invalid`, never `pass`.
Files: manifest.json, summary.json, requests.csv, samples.csv, gc.log, k6-summary.json, k6.log, statements.json, statements.csv (gitignored:
k6-raw.json.gz, server.log).
"""
import argparse
import csv
import datetime as dt
import gzip
import json
import os
import platform
import random
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from collections import Counter, defaultdict

import common as c
import seed as seedmod

BENCH_PROFILE = {"concurrency_limit": 8, "admission_ms": 1000, "connection_ms": 1000, "statement_ms": 2000, "lock_ms": 500,
                 "request_budget_ms": 5000, "client_timeout_ms": 5000, "minimum_idle": "equal to pool size",
                 "statement_timeout_scope": "transaction (SET LOCAL per query by the server); not applied to migration or codegen",
                 "jvm": "-Xmx512m -Xlog:gc:file=/tmp/gc.log",
                 "pg_stat_statements": "shared_preload_libraries=pg_stat_statements, track=top; reset after warm-up, saved after measurement (statements.json/.csv)", "limits": {"db": "2 CPUs / 2 GiB", "server": "2 CPUs / 1 GiB"}}
RATE = 5
SERVER_URL = f"http://127.0.0.1:{c.SERVER_PORT}"


def now():
    return dt.datetime.now(dt.timezone.utc)


def stamp(t=None):
    return (t or now()).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


# ---------------------------------------------------------------- fixtures

def gen_bodies(salt, count, path):
    rng = random.Random(c.BODY_SEED)
    pad = c.pad_source(rng)
    installs = c.installation_ids()
    base = now()
    with open(path, "w") as f:
        for seq in range(count):
            events = []
            for j in range(c.EVENTS_PER_BATCH):
                name = rng.choices(c.EVENT_NAMES, c.NAME_WEIGHTS)[0]
                envelope = {"contractVersion": "1", "eventId": c.event_id(salt, seq * c.EVENTS_PER_BATCH + j), "name": name,
                            "occurredAt": c.iso_ms(base - dt.timedelta(milliseconds=rng.randrange(60_000))), "sdkVersion": "0.1.0"}
                props = c.with_note(rng, pad, envelope, c.make_properties(rng, name))
                events.append(dict(envelope, properties=props))
            f.write(json.dumps({"installationId": rng.choice(installs), "events": events}, separators=(",", ":")) + "\n")


# ---------------------------------------------------------------- environment facts

def sh(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    return (r.stdout or r.stderr).strip()


def inspect(container):
    d = json.loads(sh("docker", "inspect", container))[0]
    h = d["HostConfig"]
    return {"image_id": d["Image"], "nano_cpus": h["NanoCpus"], "cpu_quota": h["CpuQuota"], "memory_bytes": h["Memory"],
            "memory_swap_bytes": h["MemorySwap"]}


def environment_facts():
    info = json.loads(sh("docker", "info", "--format", "{{json .}}"))
    pg = json.loads(sh("docker", "inspect", c.DB_CONTAINER))[0]["Config"]["Image"]
    return {
        "host": {"model": sh("sysctl", "-n", "machdep.cpu.brand_string"), "cpus": sh("sysctl", "-n", "hw.ncpu"),
                 "memory_bytes": sh("sysctl", "-n", "hw.memsize"), "arch": platform.machine(), "os": sh("sw_vers", "-productVersion"),
                 "storage": "; ".join(l.strip() for l in sh("diskutil", "info", "/").splitlines()
                                      if re.search(r"Solid State|Device / Media Name|Protocol", l))},
        "docker": {"version": info.get("ServerVersion"), "os": info.get("OperatingSystem"), "vm_cpus": info.get("NCPU"),
                   "vm_memory_bytes": info.get("MemTotal"), "storage_driver": info.get("Driver"),
                   "data_volume": "local named volume signaldock-perf_signaldock-db"},
        "tools": {"k6": sh("k6", "version"), "python": platform.python_version(), "compose": sh("docker", "compose", "version"),
                  "java": sh("docker", "exec", c.SERVER_CONTAINER, "java", "-version"),
                  "postgres": c.psql("SELECT version()", db="postgres")},
        "images": {"db": pg, "db_inspect": inspect(c.DB_CONTAINER), "server_inspect": inspect(c.SERVER_CONTAINER),
                   "server_image_tag": "signaldock-perf-server:local"},
    }


KEY_SETTINGS = ("fsync", "synchronous_commit", "full_page_writes", "shared_buffers", "work_mem", "effective_cache_size",
                "max_connections", "checkpoint_timeout", "max_wal_size", "random_page_cost", "default_statistics_target",
                "jit", "wal_level", "autovacuum")


def pg_settings():
    names = ",".join(f"'{n}'" for n in KEY_SETTINGS)
    rows = c.psql(f"SELECT name, setting, coalesce(unit,''), source FROM pg_settings WHERE source <> 'default' OR name IN ({names}) ORDER BY name",
                  db="postgres")
    return [dict(zip(("name", "setting", "unit", "source"), r.split("|"))) for r in rows.splitlines()]


HARNESS_SQL = re.compile(r"pg_stat_activity|pg_stat_statements|pg_stat_database|pg_stat_wal|pg_stat_user_tables|pg_database_size|"
                         r"pg_table_size|pg_indexes_size|pg_backend_pid|pg_terminate_backend|^ANALYZE|^CHECKPOINT|^SELECT count\(\*\)", re.I)


def save_statements(out):
    """Per-statement counters of the bench database since the reset after warm-up. Query text is as PostgreSQL normalizes it.
    Statements run by this runner (monitoring queries) stay in the files with harness=true, so nothing is hidden."""
    q = ("SELECT coalesce(json_agg(t), '[]') FROM (SELECT queryid::text AS queryid, calls, rows, total_exec_time, mean_exec_time, "
         "max_exec_time, query FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname='bench') "
         "ORDER BY total_exec_time DESC) t")
    rows = json.loads(c.psql(q))
    for r in rows:
        r["harness"] = bool(HARNESS_SQL.search(r["query"].strip()))
    (out / "statements.json").write_text(json.dumps(rows, indent=1) + "\n")
    with open(out / "statements.csv", "w", newline="") as f:
        w = csv.DictWriter(f, ["queryid", "calls", "rows", "total_exec_time", "mean_exec_time", "max_exec_time", "harness", "query"])
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def cgroup(container):
    out = {}
    for f in ("cpu.stat", "memory.events", "memory.peak", "memory.current"):
        r = subprocess.run(["docker", "exec", container, "cat", f"/sys/fs/cgroup/{f}"], capture_output=True, text=True)
        for line in r.stdout.split("\n"):
            parts = line.split()
            if len(parts) == 2:
                out[f"{f}:{parts[0]}"] = int(parts[1])
            elif len(parts) == 1:
                out[f] = int(parts[0])
    return out


def db_snapshot():
    q = ("SELECT (SELECT wal_bytes FROM pg_stat_wal), blks_read, blks_hit, xact_commit, xact_rollback, "
         "(SELECT coalesce(n_tup_ins,0) FROM pg_stat_user_tables WHERE relname='events'), "
         "pg_database_size(current_database()), pg_table_size('events'), pg_indexes_size('events') "
         "FROM pg_stat_database WHERE datname=current_database()")
    v = c.psql(q).split("|")
    keys = ("wal_bytes", "blks_read", "blks_hit", "xact_commit", "xact_rollback", "n_tup_ins", "database_bytes", "events_table_bytes", "events_index_bytes")
    return dict(zip(keys, map(int, v)), ts=stamp())


# ---------------------------------------------------------------- sampler

class Sampler(threading.Thread):
    def __init__(self, k6_pid):
        super().__init__(daemon=True)
        self.k6_pid, self.rows, self.stop = k6_pid, [], threading.Event()

    def run(self):
        while not self.stop.is_set():
            t0 = time.time()
            row = {"ts": stamp()}
            ps = sh("ps", "-o", "%cpu=,rss=", "-p", str(self.k6_pid)).split()
            if len(ps) == 2:
                row["k6_cpu_pct"], row["k6_rss_kb"] = float(ps[0]), int(ps[1])
            host = sh("sh", "-c", "ps -A -o %cpu= | awk '{s+=$1} END {print s}'")
            row["host_cpu_pct_sum"] = float(host or 0)
            for line in sh("docker", "stats", "--no-stream", "--format", "{{json .}}", c.DB_CONTAINER, c.SERVER_CONTAINER).splitlines():
                d = json.loads(line)
                tag = "db" if d["Name"] == c.DB_CONTAINER else "server"
                row[f"{tag}_cpu_pct"] = float(d["CPUPerc"].rstrip("%"))
                used = d["MemUsage"].split("/")[0].strip()
                num = float(re.match(r"[\d.]+", used).group())
                row[f"{tag}_mem_mib"] = round(num * {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "B": 1 / 1048576}[used[len(re.match(r"[\d.]+", used).group()):]], 1)
            try:
                act = c.psql("SELECT coalesce(state,'none'), count(*) FROM pg_stat_activity WHERE datname='bench' "
                             "AND application_name NOT LIKE 'psql%' GROUP BY 1")
                row["pg_conns"] = {a.split("|")[0]: int(a.split("|")[1]) for a in act.splitlines()}
            except RuntimeError:
                pass
            self.rows.append(row)
            self.stop.wait(max(0, 5 - (time.time() - t0)))


# ---------------------------------------------------------------- k6 results

def read_k6(raw_path, k6_summary):
    reqs, blocked, counters = {}, {}, defaultdict(float)
    with open(raw_path) as f:
        for line in f:
            if '"type":"Point"' in line and ('"metric":"req_ms"' in line or '"metric":"blocked_ms"' in line):
                d = json.loads(line)
                tg = d["data"].get("tags", {})
                key = (tg["ep"], tg.get("seq") or tg.get("tick"), tg.get("vu"))
                if d["metric"] == "req_ms":
                    reqs[key] = {"time": d["data"]["time"], "ep": tg["ep"], "phase": tg["phase"], "outcome": tg["outcome"],
                                 "status": tg["status"], "id": key[1], "vu": tg.get("vu"), "at_ms": int(tg["at"]), "duration_ms": d["data"]["value"]}
                else:
                    blocked[key] = d["data"]["value"]
            elif '"type":"Point"' in line and any(f'"metric":"{m}"' in line for m in (
                    "ticks_total", "ticks_skipped", "agg_wrong", "acks_accepted", "acks_duplicate", "acks_rejected", "bodies_exhausted")):
                d = json.loads(line)
                tg = d["data"].get("tags", {})
                counters[(d["metric"], tg.get("ep", ""), tg.get("phase", ""))] += d["data"]["value"]
    for k, r in reqs.items():
        r["blocked_ms"] = blocked.get(k)
    dropped = k6_summary["metrics"].get("dropped_iterations", {})
    dropped = dropped.get("count", dropped.get("values", {}).get("count", 0))
    return sorted(reqs.values(), key=lambda r: r["time"]), counters, dropped


def parse_time(s):
    s = s.replace("Z", "+00:00")
    m = re.match(r"(.*\.)(\d+)(.*)", s)  # Python 3.9 accepts only 3 or 6 fractional digits
    return dt.datetime.fromisoformat(m[1] + m[2].ljust(6, "0")[:6] + m[3] if m else s)


def summarize_requests(rows, phase):
    out = {}
    for ep in ("collect", "list", "by-name", "by-time"):
        sel = [r for r in rows if r["ep"] == ep and r["phase"] == phase]
        d = sorted(r["duration_ms"] for r in sel)
        b = [r["blocked_ms"] for r in sel if r["blocked_ms"] is not None]
        out[ep] = {"samples": len(sel), "outcomes": dict(Counter(r["outcome"] for r in sel)),
                   "p50_ms": c.percentile(d, 50), "p95_ms": c.percentile(d, 95), "max_ms": d[-1] if d else None,
                   "min_ms": d[0] if d else None, "client_blocked_connecting_max_ms": max(b) if b else None,
                   "duration_includes": "sending+waiting+receiving; connection wait (blocked+connecting) reported separately"}
    return out


def parse_server_log(path, bounds):
    """Stage timings and pool counters per phase. Log lines carry HH:mm:ss.SSS in UTC."""
    stages = defaultdict(lambda: defaultdict(list))
    pool = defaultdict(lambda: {"max_active": 0, "max_pending": 0, "samples": 0})
    day, last = bounds["start"].date(), None
    for line in open(path, errors="replace"):
        m = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d{3}) ", line)
        if not m:
            continue
        sec = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
        if last is not None and sec < last - 3600:
            day += dt.timedelta(days=1)
        last = sec
        t = dt.datetime.combine(day, dt.time(int(m[1]), int(m[2]), int(m[3]), int(m[4]) * 1000), dt.timezone.utc)
        phase = "before" if t < bounds["start"] else "warmup" if t < bounds["measure"] else "measure" if t < bounds["end"] else "after"
        s = re.search(r"stage=(\w+) elapsedMs=(\d+)", line)
        if s:
            stages[phase][s[1]].append(int(s[2]))
        p = re.search(r"pool active=(\d+) idle=(\d+) pending=(\d+) total=(\d+)", line)
        if p:
            q = pool[phase]
            q["samples"] += 1
            q["max_active"], q["max_pending"] = max(q["max_active"], int(p[1])), max(q["max_pending"], int(p[3]))
    res = {}
    for phase, d in stages.items():
        res[phase] = {st: {"count": len(v), "p50_ms": c.percentile(sorted(v), 50), "p95_ms": c.percentile(sorted(v), 95), "max_ms": max(v)}
                      for st, v in d.items()}
    return res, dict(pool)


def parse_gc(path):
    pauses = [float(m[1]) for m in (re.search(r"Pause .*? (\d+\.\d+)ms", l) for l in open(path, errors="replace")) if m]
    return {"pause_count": len(pauses), "pause_total_ms": round(sum(pauses), 1), "pause_max_ms": max(pauses) if pauses else 0}


def cg_delta(a, b):
    return {k: b[k] - a.get(k, 0) for k in b if k.startswith(("cpu.stat:", "memory.events:"))}


def sample_stats(samples, t0, t1):
    sel = [s for s in samples if t0 <= parse_time(s["ts"]) < t1]
    out = {"samples": len(sel)}
    for key in ("k6_cpu_pct", "host_cpu_pct_sum", "db_cpu_pct", "server_cpu_pct", "db_mem_mib", "server_mem_mib", "k6_rss_kb"):
        v = [s[key] for s in sel if key in s]
        if v:
            out[key] = {"avg": round(statistics.mean(v), 1), "max": max(v)}
    return out


# ---------------------------------------------------------------- checks

def api_get(path, key):
    req = urllib.request.Request(SERVER_URL + path, headers={"Authorization": f"Bearer {key}", "SignalDock-Contract-Version": "1"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def aggregate_check(frm, to, key, n_days):
    """Post-run: API results versus SQL for the same window (names, day buckets, first list page)."""
    win = f"from={frm}&to={to}"
    cond = f"received_at >= '{frm}' AND received_at < '{to}'"
    names = api_get(f"/v1/event-counts/by-name?{win}", key)
    sql_names = {r.split("|")[0]: int(r.split("|")[1]) for r in c.psql(f"SELECT name, count(*) FROM events WHERE {cond} GROUP BY 1").splitlines()}
    api_names = {i["name"]: i["count"] for i in names["items"]}
    times = api_get(f"/v1/event-counts/by-time?{win}&bucket=day", key)
    api_days = {parse_time(i["start"]).timestamp(): i["count"] for i in times["items"]}
    sql_days = {}
    for r in c.psql(f"SELECT extract(epoch FROM date_trunc('day', received_at AT TIME ZONE '{c.ZONE}') AT TIME ZONE '{c.ZONE}'), count(*) "
                    f"FROM events WHERE {cond} GROUP BY 1").splitlines():
        e, n = r.split("|")
        sql_days[float(e)] = int(n)
    first = api_get(f"/v1/events?{win}&limit=50", key)
    sql_first = c.psql(f"SELECT event_id FROM events WHERE {cond} ORDER BY received_at DESC, event_id DESC LIMIT 50").splitlines()
    day_ok = all(api_days.get(k, 0) == sql_days.get(k, 0) for k in set(api_days) | set(sql_days))
    return {"names_ok": api_names == sql_names, "days_ok": day_ok, "list_first_page_ok": [i["eventId"] for i in first["items"]] == sql_first,
            "sql_total": sum(sql_names.values()), "api_total": sum(api_names.values())}


def reconcile(salt, rows, w_seqs):
    """Compares what k6 saw per request with the rows stored for this run's IDs."""
    outcome = {int(r["id"]): r["outcome"] for r in rows if r["ep"] == "collect"}
    stored = Counter()
    for line in c.psql(f"SELECT (('x'||right(event_id::text,12))::bit(48)::bigint) FROM events WHERE event_id::text LIKE '{salt}-%'").splitlines():
        stored[int(line) // c.EVENTS_PER_BATCH] += 1
    res = {"requests_with_result": len(outcome), "rows_for_run_ids": sum(stored.values())}
    ok = [s for s, o in outcome.items() if o == "ok"]
    res["ok_requests"] = len(ok)
    res["ok_requests_not_fully_stored"] = sum(1 for s in ok if stored[s] != c.EVENTS_PER_BATCH)
    failed = [s for s, o in outcome.items() if o != "ok"]
    res["failed_requests"] = len(failed)
    res["rows_stored_after_failed_requests"] = sum(stored[s] for s in failed)
    res["rows_for_ids_without_result"] = sum(n for s, n in stored.items() if s not in outcome)
    res["unique_row_mismatch"] = res["ok_requests_not_fully_stored"] + 0
    res["duplicate_ids_in_db"] = int(c.psql(f"SELECT count(*) - count(DISTINCT event_id) FROM events WHERE event_id::text LIKE '{salt}-%'"))
    return res


# ---------------------------------------------------------------- main

def wait_health(timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(SERVER_URL + "/health", timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            time.sleep(0.5)
    raise RuntimeError("server not healthy")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed-size", type=int, required=True, choices=[10000, 100000])
    ap.add_argument("--dist", required=True, choices=["uniform", "recent70"])
    ap.add_argument("--window", type=int, required=True, choices=[1, 7, 30])
    ap.add_argument("--clients", type=int, required=True, choices=[1, 5])
    ap.add_argument("--pool", type=int, required=True, choices=[2, 4, 8])
    ap.add_argument("--warmup", type=int, default=60)
    ap.add_argument("--measure", type=int, default=300)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--rep", type=int, default=1)
    ap.add_argument("--position", default="", help="pool order of this repetition, e.g. 2-4-8")
    ap.add_argument("--pilot", action="store_true", help="pilot: recorded but not one of the measured runs")
    ap.add_argument("--out", default=None)
    guarded(ap.parse_args())


def guarded(a):
    """An interrupted run keeps its directory with ERROR.txt (invalid runs are preserved with their reason)."""
    try:
        execute(a)
    except BaseException:
        import traceback
        out = c.Path(a.out) if a.out else c.RESULTS / a.run_id
        if out.exists():
            (out / "ERROR.txt").write_text(traceback.format_exc())
        c.compose("rm", "-sf", "server", check=False)
        raise


def execute(a):
    out = c.Path(a.out) if a.out else c.RESULTS / a.run_id
    if out.exists():
        sys.exit(f"{out} exists; run ids are never reused")
    out.mkdir(parents=True)
    env = c.ensure_env()
    meta = json.loads(seedmod.meta_path(a.seed_size, a.dist).read_text())
    T = dt.datetime.fromisoformat(meta["reference_time_T"])
    frm, to = c.iso_ms(T - dt.timedelta(days=a.window)), c.iso_ms(T)
    salt = c.run_salt(a.run_id)
    times = {"run_start": stamp()}
    manifest = {"run_id": a.run_id, "status": "started", "counts_as_measured_run": not a.pilot, "pilot": a.pilot,
                "git": c.git_state(), "plan": "perf/../.scratch/signaldock-portfolio/performance-plan.md",
                "repetition": a.rep, "pool_order": a.position, "pool_size": a.pool,
                "combination": {"seed_size": a.seed_size, "distribution": a.dist, "window_days": a.window, "clients": a.clients},
                "reference_time_T": meta["reference_time_T"], "window": {"from": frm, "to": to}, "seed": meta,
                "load": {"collection": {"rate_rps": RATE, "events_per_batch": c.EVENTS_PER_BATCH, "preallocated_vus": 30, "max_vus": 30,
                                        "timeout_ms": 5000, "retries": 0, "executor": "constant-arrival-rate"},
                         "dashboard": {"clients": a.clients, "tick_ms": 5000, "requests_per_tick_per_client": 3,
                                       "endpoints": ["GET /v1/events?limit=50 (no name filter)", "GET /v1/event-counts/by-name",
                                                     "GET /v1/event-counts/by-time (default bucket)"]},
                         "warmup_s": a.warmup, "measure_s": a.measure, "run_id_salt": salt},
                "benchmark_profile": BENCH_PROFILE, "times": times,
                "credentials": "not recorded (random per-machine test keys in perf/work/env)"}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    seed_db = seedmod.seed_name(a.seed_size, a.dist)
    c.log(f"[{a.run_id}] restore {seed_db} -> bench")
    c.compose("up", "-d", "--wait", "db")
    c.wait_db()
    c.compose("rm", "-sf", "server")
    c.psql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='bench'", db="postgres")
    c.psql("DROP DATABASE IF EXISTS bench", db="postgres")
    times["restore_start"] = stamp()
    c.psql(f"CREATE DATABASE bench TEMPLATE {seed_db}", db="postgres")
    assert c.psql("SELECT 1 FROM pg_extension WHERE extname='pg_stat_statements'") == "1", \
        "pg_stat_statements missing in the seed template; run perf/seed.py build again"
    c.psql("ANALYZE")
    c.psql("CHECKPOINT")
    times["restore_end"] = stamp()
    rows0 = int(c.psql("SELECT count(*) FROM events"))
    assert rows0 == a.seed_size, f"restore row count {rows0}"
    cond = f"received_at >= '{frm}' AND received_at < '{to}'"
    exp_names = {r.split("|")[0]: int(r.split("|")[1]) for r in c.psql(f"SELECT name, count(*) FROM events WHERE {cond} GROUP BY 1").splitlines()}
    expect_total = sum(exp_names.values())
    manifest["expected_window"] = {"total": expect_total, "by_name": exp_names}
    bodies = c.WORK / "bodies" / f"{a.run_id}.jsonl"
    bodies.parent.mkdir(parents=True, exist_ok=True)
    gen_bodies(salt, (a.warmup + a.measure) * RATE + 100, bodies)
    manifest["load"]["request_fixture_sha256"] = c.sha256_file(bodies)

    c.log("start server")
    c.compose("up", "-d", "--no-deps", "server", env={"PERF_POOL_SIZE": str(a.pool)})
    wait_health()
    end = time.time() + 60
    ready = False
    while time.time() < end:
        n = int(c.psql("SELECT count(*) FROM pg_stat_activity WHERE datname='bench' AND application_name NOT LIKE 'psql%'"))
        if n >= a.pool:
            ready = True
            break
        time.sleep(1)
    times["server_ready"] = stamp()
    manifest["pool_ready"] = ready
    manifest["environment"] = environment_facts()
    manifest["postgres_settings"] = pg_settings()
    cg0 = {"db": cgroup(c.DB_CONTAINER), "server": cgroup(c.SERVER_CONTAINER)}
    snap0 = db_snapshot()
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    k6env = {**os.environ, "WARMUP_S": str(a.warmup), "MEASURE_S": str(a.measure), "CLIENTS": str(a.clients), "BASE_URL": SERVER_URL,
             "SALT": salt, "BODIES": str(bodies), "FROM": frm, "TO": to, "EXPECT_TOTAL": str(expect_total),
             "EXPECT_NAMES": json.dumps(exp_names), "RATE": str(RATE), "COLLECTION_KEY": env["SIGNALDOCK_COLLECTION_KEY"],
             "QUERY_KEY": env["SIGNALDOCK_QUERY_KEY"]}
    raw = out / "k6-raw.json"
    c.log(f"k6: warm-up {a.warmup}s + measure {a.measure}s")
    times["k6_start"] = stamp()
    k6log = open(out / "k6.log", "w")
    k6 = subprocess.Popen(["k6", "run", "--quiet", "--no-usage-report", "--out", f"json={raw}", "--summary-export", str(out / "k6-summary.json"),
                           str(c.PERF / "load.js")], env=k6env, stdout=k6log, stderr=subprocess.STDOUT)
    sampler = Sampler(k6.pid)
    sampler.start()
    snaps = {}
    try:
        time.sleep(a.warmup + 3)  # k6 initialisation is a few seconds; exact boundaries come from request timestamps
        snaps["after_warmup"] = db_snapshot()
        c.psql("SELECT pg_stat_statements_reset()")
        times["statements_reset"] = stamp()
        k6.wait(timeout=a.measure + 120)
    finally:
        times["k6_end"] = stamp()
        sampler.stop.set()
        if k6.poll() is None:
            k6.kill()
    n_statements = save_statements(out)  # before drain and post checks, which run their own queries
    snaps["after_k6"] = db_snapshot()

    # drain: no active DB work may remain before the next run
    t_drain = time.time()
    drained = False
    while time.time() - t_drain < 30:
        busy = int(c.psql("SELECT count(*) FROM pg_stat_activity WHERE datname='bench' AND state <> 'idle' "
                          "AND application_name NOT LIKE 'psql%' AND pid <> pg_backend_pid()"))
        if busy == 0:
            drained = True
            break
        time.sleep(0.5)
    times["drain_end"] = stamp()
    manifest["drain"] = {"seconds": round(time.time() - t_drain, 1), "db_idle": drained}
    sampler.join(timeout=20)
    cg1 = {"db": cgroup(c.DB_CONTAINER), "server": cgroup(c.SERVER_CONTAINER)}
    snaps["after_drain"] = db_snapshot()

    # parse k6
    k6_summary = json.loads((out / "k6-summary.json").read_text())
    rows, counters, dropped = read_k6(raw, k6_summary)
    # `time` is when k6 emitted the point (request end); `at_ms` is the request start offset of a collection request.
    offsets = sorted((parse_time(r["time"]) - dt.timedelta(milliseconds=r["at_ms"] + r["duration_ms"])).timestamp()
                     for r in rows if r["ep"] == "collect")
    start_wall = dt.datetime.fromtimestamp(offsets[len(offsets) // 2], dt.timezone.utc)
    bounds = {"start": start_wall, "measure": start_wall + dt.timedelta(seconds=a.warmup),
              "end": start_wall + dt.timedelta(seconds=a.warmup + a.measure)}
    times.update({"test_start_wall": stamp(bounds["start"]), "measurement_start_wall": stamp(bounds["measure"]),
                  "measurement_end_wall": stamp(bounds["end"])})
    with gzip.open(str(raw) + ".gz", "wt") as g, open(raw) as f:
        shutil.copyfileobj(f, g)
    raw.unlink()
    with open(out / "requests.csv", "w", newline="") as f:
        w = csv.DictWriter(f, ["time", "ep", "phase", "outcome", "status", "id", "vu", "at_ms", "duration_ms", "blocked_ms"])
        w.writeheader()
        w.writerows(rows)
    samples = sampler.rows
    with open(out / "samples.csv", "w", newline="") as f:
        keys = sorted({k for s in samples for k in s if k != "pg_conns"})
        w = csv.DictWriter(f, keys + ["pg_conns"])
        w.writeheader()
        w.writerows({**s, "pg_conns": json.dumps(s.get("pg_conns", {}))} for s in samples)

    # server logs, gc log, post checks
    (out / "server.log").write_text(c.run(["docker", "logs", c.SERVER_CONTAINER], check=False).stdout +
                                    c.run(["docker", "logs", c.SERVER_CONTAINER], check=False).stderr)
    (out / "gc.log").write_text(c.run(["docker", "exec", c.SERVER_CONTAINER, "cat", "/tmp/gc.log"], check=False).stdout)
    stage, pool = parse_server_log(out / "server.log", bounds)
    check = aggregate_check(frm, to, env["SIGNALDOCK_QUERY_KEY"], a.window)
    started_measure = sum(1 for r in rows if r["ep"] == "collect" and r["phase"] == "measure")  # k6 also starts one extra iteration at t=duration (phase "tail")
    rec = reconcile(salt, rows, None)
    seed_rows_after = int(c.psql(f"SELECT count(*) FROM events WHERE event_id::text NOT LIKE '{salt}-%'"))
    final_sizes = db_snapshot()
    c.compose("rm", "-sf", "server")
    c.psql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='bench'", db="postgres")
    bodies.unlink(missing_ok=True)
    times["run_end"] = stamp()

    meas = summarize_requests(rows, "measure")
    warm = summarize_requests(rows, "warmup")
    c_get = lambda m, ep="", ph="": counters.get((m, ep, ph), 0)
    skipped = {ep: c_get("ticks_skipped", ep, "measure") for ep in ("list", "by-name", "by-time")}
    reasons, invalid = [], []
    scheduled = a.measure * RATE
    if c_get("bodies_exhausted"):
        invalid.append("request fixtures exhausted")
    if dropped:
        invalid.append(f"dropped_iterations={int(dropped)} (generator VUs or generator saturation)")
    if started_measure != scheduled:
        invalid.append(f"measurement started {started_measure} of {scheduled} scheduled collection iterations")
    if not manifest["pool_ready"]:
        invalid.append("pool not ready before warm-up")
    if not drained:
        invalid.append("DB work still active after 30 s drain")
    for phase, s in (("warm-up", warm), ("measurement", meas)):
        bad = {ep: {k: v for k, v in x["outcomes"].items() if k != "ok"} for ep, x in s.items()}
        bad = {ep: v for ep, v in bad.items() if v}
        if bad:
            reasons.append(f"{phase} non-ok outcomes: {bad}")
    limits = {"collect": 500, "list": 500, "by-name": 1000, "by-time": 1000}
    for ep, lim in limits.items():
        p95 = meas[ep]["p95_ms"]
        if p95 is None or p95 > lim:
            reasons.append(f"{ep} p95 {p95} ms > {lim} ms")
    if any(skipped.values()):
        reasons.append(f"Dashboard skipped ticks in measurement: {skipped}")
    if c_get("acks_duplicate") or c_get("acks_rejected"):
        reasons.append(f"unexpected ACK statuses duplicate={c_get('acks_duplicate')} rejected={c_get('acks_rejected')}")
    if rec["ok_requests_not_fully_stored"] or rec["duplicate_ids_in_db"] or rec["rows_for_ids_without_result"]:
        reasons.append(f"ID reconciliation mismatch: {rec}")
    if seed_rows_after != a.seed_size:
        reasons.append(f"seed rows changed: {seed_rows_after} != {a.seed_size}")
    agg_wrong = sum(v for (m, _, _), v in counters.items() if m == "agg_wrong")
    if agg_wrong or not (check["names_ok"] and check["days_ok"] and check["list_first_page_ok"]):
        reasons.append(f"aggregation errors: in-run wrong={int(agg_wrong)}, post-run check={check}")
    verdict = "invalid" if invalid else "fail" if reasons else "pass"

    summary = {
        "run_id": a.run_id, "verdict": verdict, "invalid_reasons": invalid, "fail_reasons": reasons,
        "counts_as_measured_run": not a.pilot,
        "load_validity": {"scheduled_measurement_iterations": scheduled, "started_measurement_iterations": started_measure,
                          "dropped_iterations": dropped, "generator": sample_stats(samples, bounds["measure"], bounds["end"])},
        "measurement": meas, "warmup": warm,
        "acks": {k: c_get(k) for k in ("acks_accepted", "acks_duplicate", "acks_rejected")},
        "dashboard_ticks": {ep: {"measure_ticks": c_get("ticks_total", ep, "measure"), "measure_skipped": skipped[ep],
                                 "warmup_ticks": c_get("ticks_total", ep, "warmup"), "warmup_skipped": c_get("ticks_skipped", ep, "warmup")}
                            for ep in skipped},
        "dashboard_request_counts_measure": {ep: meas[ep]["samples"] for ep in ("list", "by-name", "by-time")},
        "reconciliation": rec, "aggregate_check": check, "seed_rows_after_run": seed_rows_after,
        "db": {"snapshot_before": snap0, "after_warmup": snaps["after_warmup"], "after_k6": snaps["after_k6"], "after_drain": snaps["after_drain"],
               "final": final_sizes, "rows_added_total": snaps["after_drain"]["n_tup_ins"] - snap0["n_tup_ins"],
               "rows_added_by_end_of_warmup_approx": snaps["after_warmup"]["n_tup_ins"] - snap0["n_tup_ins"],
               "wal_bytes_run": snaps["after_drain"]["wal_bytes"] - snap0["wal_bytes"],
               "buffer_before_after_warmup": {"hit_ratio_warmup": _ratio(snap0, snaps["after_warmup"]),
                                              "hit_ratio_measure": _ratio(snaps["after_warmup"], snaps["after_k6"]),
                                              "note": "shared buffer hits are not proof of no physical disk I/O"}},
        "server_stage_timings": stage, "hikari_pool_counters": pool, "gc": parse_gc(out / "gc.log"),
        "cgroup_deltas": {k: cg_delta(cg0[k], cg1[k]) for k in cg0},
        "cgroup_final": {"db": cg1["db"], "server": cg1["server"]},
        "containers_measure": sample_stats(samples, bounds["measure"], bounds["end"]),
        "metrics_collected": ["per-request client latency/outcome (k6)", "ACK reconciliation", "DB sizes, WAL, buffer counters",
                              "server stage timings: admission_wait, connection_wait, commit, lock/statement timeout, rollback",
                              "Hikari active/idle/pending/total once per second", "container CPU/memory (docker stats, 5 s)",
                              "cgroup CPU throttling and OOM counters", f"pg_stat_statements per statement (calls, rows, total/mean/max time): {n_statements} statements", "JVM GC pauses", "generator CPU/RSS and host CPU"],
        "metrics_not_collected": ["Hikari acquire time other than the connection_wait stage",
                                  "native JVM memory beyond container memory", "physical disk I/O"],
    }
    manifest["status"] = "completed"
    (out / "summary.json").write_text(json.dumps(summary, indent=2, default=str) + "\n")
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str) + "\n")
    c.log(f"[{a.run_id}] verdict={verdict} invalid={invalid} fail={reasons}")
    print(json.dumps({"run_id": a.run_id, "verdict": verdict, "out": str(out)}))


def _ratio(a, b):
    hit, read = b["blks_hit"] - a["blks_hit"], b["blks_read"] - a["blks_read"]
    return round(hit / (hit + read), 4) if hit + read else None


if __name__ == "__main__":
    main()
