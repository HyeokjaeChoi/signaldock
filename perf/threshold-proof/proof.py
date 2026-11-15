#!/usr/bin/env python3
"""Proof that the k6 thresholds of perf/load.js and perf/overload.js fail on a fault and pass on a clean run (ADR-0049 decision 7: absolute thresholds only).
ADR-0049, amendment of ticket 04. Standard library only; needs k6 on PATH. No Docker, no SignalDock server, no demo VM.

Run: python3 perf/threshold-proof/proof.py [--only load|overload] [--out perf/threshold-proof/results.md]

A mock HTTP server answers the endpoints of the two scripts. Each case starts it with at most one fault, runs the real k6 script
with the same flags as the run scripts, and compares the thresholds that `--summary-export` marks as crossed with the expected set.
A clean case must exit 0 with none crossed; a fault case must exit 99 with exactly the named threshold crossed. Then the Python side
is checked: common.k6_exit_reasons gives the verdict reason, and the Python summaries parse the k6 output of every case, also after
exit 99 (run_one.read_k6, overload.read_raw). The exit status is 0 only when every case matched.
"""
import argparse
import copy
import datetime as dt
import http.server
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

PERF = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PERF))
import common as c  # noqa: E402
import overload as ov  # noqa: E402
import run_one as ro  # noqa: E402

TOTAL, NAMES = 1000, {"product_view": 700, "cart_add": 200, "purchase": 100}  # what the mock aggregates return (EXPECT_TOTAL, EXPECT_NAMES)


# ---------------------------------------------------------------- mock server

class Rule:
    """Applies to endpoint `ep` ('collect', 'events', 'by-name', 'by-time') while `lo <= seconds since the first request < hi`.
    Effects (delay_ms and status apply to every `every`-th request): delay_ms; status (retry_after None = header missing); stall (hold until `hi`)."""

    def __init__(self, ep, lo=0, hi=1e9, delay_ms=0, status=None, every=1, retry_after="1", stall=False):
        self.ep, self.lo, self.hi, self.delay_ms, self.status, self.every, self.retry_after, self.stall = ep, lo, hi, delay_ms, status, every, retry_after, stall
        self.n = 0


class Mock:
    def __init__(self, rules):
        self.rules, self.t0, self.lock = rules, None, threading.Lock()
        mock = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a):
                pass

            def reply(self, status, body, retry_after=None):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                if retry_after is not None:
                    self.send_header("Retry-After", retry_after)
                self.end_headers()
                self.wfile.write(raw)

            def serve(self, ep, ok_body):
                with mock.lock:
                    if mock.t0 is None:
                        mock.t0 = time.monotonic()
                    now = time.monotonic() - mock.t0
                    hits = [r for r in mock.rules if r.ep == ep and r.lo <= now < r.hi]
                    for r in hits:
                        r.n += 1
                status, retry_after = None, None
                for r in hits:
                    if r.stall:
                        time.sleep(max(r.hi - (time.monotonic() - mock.t0), 0))
                    if r.delay_ms and r.n % r.every == 0:
                        time.sleep(r.delay_ms / 1000)
                    if r.status and r.n % r.every == 0:
                        status, retry_after = r.status, r.retry_after
                if status:
                    return self.reply(status, {"code": "TEMPORARILY_UNAVAILABLE" if status == 503 else "INTERNAL"}, retry_after)
                self.reply(200, ok_body())

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.serve("collect", lambda: {"results": [{"index": j, "eventId": e["eventId"], "status": "accepted"} for j, e in enumerate(body["events"])]})

            def do_GET(self):
                path = self.path.split("?")[0]
                if path == "/v1/events":
                    self.serve("events", lambda: {"items": [{"receivedAt": f"2026-10-11T14:59:59.{999 - i:03d}Z"} for i in range(min(50, TOTAL))]})
                elif path == "/v1/event-counts/by-name":
                    self.serve("by-name", lambda: {"truncated": False, "items": [{"name": n, "count": k} for n, k in NAMES.items()]})
                elif path == "/v1/event-counts/by-time":
                    self.serve("by-time", lambda: {"items": [{"count": TOTAL}]})
                else:
                    self.reply(404, {})

        class Server(http.server.ThreadingHTTPServer):
            daemon_threads = True
            request_queue_size = 1024

            def handle_error(self, request, client_address):
                if not isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError)):  # k6 closes a connection it gave up on (the timeout case)
                    super().handle_error(request, client_address)

        self.server = Server(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def __enter__(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


# ---------------------------------------------------------------- fixtures

def write_bodies(path, salt, n):
    """Bodies of perf/load.js and overload.js: 20 events per request; only eventId matters to the mock and to the k6 ack check."""
    with open(path, "w") as f:
        for seq in range(n):
            f.write(json.dumps({"events": [{"eventId": c.event_id(salt, seq * 20 + j)} for j in range(20)]}) + "\n")


# ---------------------------------------------------------------- cases

DROPPED = "dropped_iterations: count==0"  # a generator limit: the run scripts keep the run `invalid`, they do not call it a server `fail`
THR = {  # threshold -> name used in `expect`
    "failed": "http_req_failed{phase:measure}: rate<0.01",
    "dropped": DROPPED,
}
LOAD = dict(WARMUP_S="5", MEASURE_S="20", CLIENTS="2", RATE="5")
OVER = dict(BASE_S="5", OVER_S="10", REC_S="40", RATE="5", OVER_RATE="200", HEAVY_RATE="150")
SHED = [Rule("collect", 5, 15, status=503, every=2), Rule("by-name", 5, 15, status=503, every=2)]  # what a saturated server does
O_THR = {"status": "unexpected_status: rate==0", "dropped": DROPPED}

CASES = [  # (script, case, fault in words, env of the script, mock rules, expected crossed thresholds)
    ("load", "clean", "none", LOAD, [], []),
    ("load", "failures in warm-up only", "every collect answers 500 in the first 4.5 s (warm-up is 5 s): the measure-phase submetric must not count them", LOAD,
     [Rule("collect", 0, 4.5, status=500)], []),
    ("load", "http_req_failed", "every 20th collect answers 500", LOAD, [Rule("collect", status=500, every=20)], [THR["failed"]]),
    ("load", "dropped_iterations", "40 req/s, collect held 1.5 s at 12 s (all 30 VUs busy)", dict(LOAD, RATE="40"), [Rule("collect", 12, 13.5, stall=True)], [THR["dropped"]]),
    ("load", "dropped_iterations and http_req_failed", "as the case above, and every 20th collect answers 500", dict(LOAD, RATE="40"),
     [Rule("collect", 12, 13.5, stall=True), Rule("collect", status=500, every=20)], [THR["dropped"], THR["failed"]]),
    ("overload", "clean", "every 2nd collect and by-name answer 503 in the overload phase", OVER, SHED, []),
    ("overload", "status other than 200 and 503", "every 50th collect answers 500 in the overload phase", OVER, SHED + [Rule("collect", 5, 15, status=500, every=50)], [O_THR["status"]]),
    ("overload", "status 0 (timeout)", "every 100th collect answers after 6 s, past the 5 s k6 timeout", OVER, SHED + [Rule("collect", 5, 15, delay_ms=6000, every=100)], [O_THR["status"]]),
    ("overload", "dropped_iterations", "collect held 2.5 s at 8 s (all 300 VUs busy at 200 req/s)", OVER, SHED + [Rule("collect", 8, 10.5, stall=True)], [O_THR["dropped"]]),
]
SCRIPT = {"load": PERF / "load.js", "overload": PERF / "overload.js"}


def observed(summary, entry):
    name, expr = entry.split(": ", 1)
    agg = re.match(r"(.+?)(?:<=|>=|==|!=|<|>)", expr)[1]
    m = summary["metrics"][name]
    v = m.get(agg, m.get("value"))
    return f"{entry} (observed {v:.4g})" if isinstance(v, (int, float)) else entry


def run_case(script, env_extra, rules, work):
    work.mkdir(parents=True)
    salt = "0badc0de"
    with Mock(rules) as mock:
        e = {k: int(v) for k, v in env_extra.items()}
        n = e["BASE_S"] * e["RATE"] + e["OVER_S"] * e["OVER_RATE"] + e["REC_S"] * e["RATE"] if script == "overload" else (e["WARMUP_S"] + e["MEASURE_S"]) * e["RATE"]
        write_bodies(work / "bodies.jsonl", salt, n + 300)  # + the tail iteration and slack
        env = {**os.environ, **env_extra, "BASE_URL": mock.url, "SALT": salt, "COLLECTION_KEY": "test-c", "QUERY_KEY": "test-q", "BODIES": str(work / "bodies.jsonl"),
               "FROM": "2026-09-11T15:00:00.000Z", "TO": "2026-10-11T15:00:00.000Z", "EXPECT_TOTAL": str(TOTAL), "EXPECT_NAMES": json.dumps(NAMES)}
        with open(work / "k6.log", "w") as log:
            p = subprocess.run(["k6", "run", "--quiet", "--no-usage-report", "--out", f"json={work / 'raw.json'}", "--summary-export", str(work / "summary.json"),
                                str(SCRIPT[script])], env=env, stdout=log, stderr=subprocess.STDOUT)
    return p.returncode, json.loads((work / "summary.json").read_text())


def require(cond, what):
    if not cond:  # not `assert`: python -O must not switch the proof off
        raise AssertionError(what)


def python_summaries(script, work, summary, clean):
    """The Python summaries parse the k6 output, also after exit 99. On a clean run they also check what they found. Returns a short text; raises when one breaks."""
    raw = work / "raw.json"
    if script == "load":
        rows, counters, dropped = ro.read_k6(raw, summary)
        s = ro.summarize_requests(rows, "measure")
        if clean:
            require(dropped == 0 and all(s[ep]["samples"] > 0 and s[ep]["p95_ms"] is not None for ep in s), s)
        return f"run_one.read_k6: {len(rows)} requests, dropped {dropped:g}"
    rows, counters = ov.read_raw(raw)
    ph = ov.phase_stats(rows)
    shed = ph["overload"].get("collect", {}).get("status", {}).get("503", 0)
    if clean:
        require(shed > 0 and ph["baseline"]["collect"]["status"] == {"200": ph["baseline"]["collect"]["requests"]}, ph)
    return f"overload.read_raw: {len(rows)} requests, {shed} collect 503 in overload"


def replay():
    """The thresholds applied to the committed result files, no new run: (file, script, what the file shows, crossed?)."""
    out = []
    for d in ("pilot-1", "pilot-2"):
        s = json.loads((PERF / "results" / d / "summary.json").read_text())
        n = sum(m["samples"] for m in s["measurement"].values())
        bad = sum(v for m in s["measurement"].values() for o, v in m["outcomes"].items() if o != "ok")
        dropped = s["load_validity"]["dropped_iterations"]
        out.append((f"perf/results/{d}/summary.json", "load.js", f"{bad} of {n} measure-phase requests not ok; dropped_iterations {dropped:g}", bad / n >= 0.01 or dropped > 0))
    s = json.loads((PERF / "results" / "overload-1" / "summary.json").read_text())
    statuses = sorted({st for ph in s["phases"].values() for m in ph.values() for st in m["status"]})
    out.append(("perf/results/overload-1/summary.json", "overload.js", f"answer statuses {statuses}; dropped_iterations {s['dropped_iterations']:g}",
                bool(set(statuses) - {"200", "503"}) or s["dropped_iterations"] > 0))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=("load", "overload"))
    ap.add_argument("--out", help="default: results.md next to this file, but only for a full run (--only writes nothing without --out)")
    a = ap.parse_args()
    if not shutil.which("k6"):
        sys.exit("k6 is not on PATH")
    k6_version = subprocess.run(["k6", "version"], capture_output=True, text=True).stdout.split("\n")[0]
    tmp = Path(tempfile.mkdtemp(prefix="k6-threshold-proof-"))
    rows, failed = [], 0
    for i, (script, case, fault, env_extra, rules, expect) in enumerate(CASES):
        if a.only and script != a.only:
            continue
        t = time.time()
        code, summary = run_case(script, env_extra, copy.deepcopy(rules), tmp / f"{i:02d}")  # rules count requests: no state shared between cases
        crossed = c.crossed_thresholds(summary)
        invalid, fail = c.k6_exit_reasons(code, summary)
        # dropped_iterations -> an `invalid` reason; any other crossed threshold -> a `fail` reason; both can apply (the runners rank invalid first)
        ok = (code == (99 if expect else 0) and sorted(crossed) == sorted(expect)
              and bool(invalid) == (DROPPED in expect) and bool(fail) == any(e != DROPPED for e in expect))
        note = ""
        try:
            note = python_summaries(script, tmp / f"{i:02d}", summary, clean=not expect)
        except Exception as e:  # noqa: BLE001 - any failure of a Python summary is a failed proof
            ok, note = False, f"Python summary broke: {e!r}"
        failed += not ok
        shown = "; ".join(observed(summary, x) for x in crossed) or "none"
        verdict = ("invalid, and a fail reason" if fail else "invalid") if invalid else "fail" if fail else "pass"
        rows.append((script, case, fault, code, shown, verdict, note, "ok" if ok else "MISMATCH, expected " + (str(expect) or "none")))
        print(f"[{time.time() - t:5.1f}s] {script:8} {case:24} exit {code:3} crossed: {shown}  -> {rows[-1][-1]}", flush=True)
    out = ["# k6 threshold proof (ADR-0049, amendment of ticket 04)", "",
           f"Written by `perf/threshold-proof/proof.py` on {dt.date.today()}. {k6_version}; Python {platform.python_version()}; {platform.platform()}.",
           "Each case runs the real k6 script against a mock server with one fault. `exit` is the k6 exit status (99: a threshold was crossed). "
           "`crossed` lists the thresholds that `--summary-export` marks as crossed, with the observed value. `run script verdict` is what "
           "`common.k6_exit_reasons` makes of the k6 exit (the function `perf/run_one.py` and `perf/overload.py` use): a crossed server threshold "
           "is a `fail` reason that names it; a crossed `dropped_iterations` keeps the run `invalid` (generator limit). `match` compares the "
           "crossed set and that verdict with the expected ones (exact match).", "",
           "| script | case | fault | k6 exit | crossed | run script verdict | Python summary parses the output | match |", "|---|---|---|---:|---|---|---|---|"]
    out += [f"| {s} | {cs} | {f} | {x} | {cr} | {v} | {n or '-'} | {m} |" for s, cs, f, x, cr, v, n, m in rows]
    rep_rows = replay()
    failed += sum(1 for *_, crossed in rep_rows if crossed)
    out += ["", "## Replay on committed results (no new run)", "",
            "The same thresholds read off result files that are already in the repository. `crossed` would be `yes` if the threshold had failed.", "",
            "| file | script | shows | crossed |", "|---|---|---|---|"]
    out += [f"| `{f}` | {sc} | {sh} | {'yes' if cr else 'no'} |" for f, sc, sh, cr in rep_rows]
    target = a.out or (None if a.only else str(Path(__file__).with_name("results.md")))
    if target:
        Path(target).write_text("\n".join(out) + "\n")
    print(f"{len(rows) + len(rep_rows) - failed} of {len(rows) + len(rep_rows)} checks matched; " + (f"wrote {target}" if target else "wrote nothing (--only without --out)"))
    shutil.rmtree(tmp, ignore_errors=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
