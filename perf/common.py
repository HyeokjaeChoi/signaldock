"""Shared helpers for the issue 23 server load measurement harness (ADR-0013). Standard library only."""
import datetime as dt
import hashlib
import json
import math
import os
import secrets
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PERF = ROOT / "perf"
WORK = PERF / "work"
RESULTS = PERF / "results"
ENV_FILE = WORK / "env"
PROJECT = "signaldock-perf"
DB_CONTAINER = f"{PROJECT}-db-1"
SERVER_CONTAINER = f"{PROJECT}-server-1"
BENCH_DB = "bench"
ZONE = "Asia/Seoul"
SERVER_PORT = 18080
DB_PORT = 15432
BODY_SEED = 20261012  # request fixtures use one fixed seed for every run; only the ID salt differs
EVENTS_PER_BATCH = 20
INSTALLATIONS = 100
EVENT_NAMES = ["product_view", "cart_add", "purchase"]
NAME_WEIGHTS = [70, 20, 10]
TARGET_EVENT_BYTES = 1024


def log(msg):
    print(f"[{dt.datetime.now().strftime('%H:%M:%S')}] {msg}", file=sys.stderr, flush=True)


def run(cmd, check=True, **kw):
    return subprocess.run(cmd, check=check, text=True, capture_output=True, cwd=kw.pop("cwd", ROOT), **kw)


def ensure_env():
    """Random test credentials for this local benchmark only. They live in perf/work (gitignored), never in a manifest."""
    if not ENV_FILE.exists():
        WORK.mkdir(parents=True, exist_ok=True)
        ENV_FILE.write_text("\n".join([
            f"SIGNALDOCK_COLLECTION_KEY=perf-c-{secrets.token_hex(12)}",
            f"SIGNALDOCK_QUERY_KEY=perf-q-{secrets.token_hex(12)}",
            f"SIGNALDOCK_DB_NAME={BENCH_DB}",
            "SIGNALDOCK_DB_USER=perf",
            f"SIGNALDOCK_DB_PASSWORD=perf-{secrets.token_hex(12)}",
            f"SIGNALDOCK_DB_PORT={DB_PORT}",
            f"SIGNALDOCK_SERVER_PORT={SERVER_PORT}",
            f"SIGNALDOCK_TIME_ZONE={ZONE}",
            "",
        ]))
        ENV_FILE.chmod(0o600)
    return dict(line.split("=", 1) for line in ENV_FILE.read_text().splitlines() if "=" in line)


def compose(*args, env=None, check=True, **kw):
    ensure_env()
    return subprocess.run(
        ["docker", "compose", "-p", PROJECT, "--env-file", str(ENV_FILE), "-f", "docker-compose.yml",
         "-f", "perf/compose.perf.yml", *args],
        cwd=ROOT, text=True, capture_output=True, check=check, env={**os.environ, **(env or {})}, **kw)


def psql(sql, db=BENCH_DB, check=True, stdin=None):
    """Runs SQL in the DB container over its local socket (trust authentication inside the container)."""
    user = ensure_env()["SIGNALDOCK_DB_USER"]
    r = subprocess.run(["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", user, "-d", db, "-v", "ON_ERROR_STOP=1",
                        "-At", "-F", "|", "-c", sql], text=True, capture_output=True, stdin=stdin)
    if check and r.returncode:
        raise RuntimeError(f"psql failed: {r.stderr.strip()}")
    return r.stdout.strip()


def wait_db(timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        r = subprocess.run(["docker", "exec", DB_CONTAINER, "pg_isready", "-U", ensure_env()["SIGNALDOCK_DB_USER"]],
                           capture_output=True, text=True)
        if r.returncode == 0:
            return
        time.sleep(1)
    raise RuntimeError("db not ready")


def reference_time(day=None):
    """T = Asia/Seoul midnight of `day` (default: today in Seoul), as an aware datetime."""
    from zoneinfo import ZoneInfo
    z = ZoneInfo(ZONE)
    d = dt.date.fromisoformat(day) if day else dt.datetime.now(z).date()
    return dt.datetime(d.year, d.month, d.day, tzinfo=z)


def iso_ms(t):
    """UTC RFC 3339 with exact milliseconds, as the query API requires."""
    t = t.astimezone(dt.timezone.utc)
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"


def event_id(salt, n):
    """UUIDv4-shaped; salt is 8 hex characters. perf/load.js uses the same formula."""
    return f"{salt}-0000-4000-8000-{n:012x}"


def run_salt(run_id):
    return hashlib.sha256(run_id.encode()).hexdigest()[:8]


def installation_ids():
    return [f"{i:08x}-0000-4000-8000-{i:012x}" for i in range(1, INSTALLATIONS + 1)]


def pad_source(rng):
    alphabet = "abcdefghijklmnopqrstuvwxyz0123456789 "
    return "".join(rng.choice(alphabet) for _ in range(4096))


def make_properties(rng, name):
    """Flat valid properties (string, number, boolean); only the generator's rng decides the values."""
    props = {
        "product_id": f"sku-{rng.randrange(1, 5000)}",
        "category": rng.choice(["shoes", "bags", "tops", "home", "beauty"]),
        "price": rng.randrange(1000, 200000),
        "quantity": rng.randrange(1, 5),
        "in_stock": rng.random() < 0.9,
        "currency": "KRW",
        "session_id": f"{rng.getrandbits(64):016x}",
    }
    if name == "purchase":
        props["coupon"] = rng.choice(["WELCOME", "SPRING", "NONE"])
    return props


def with_note(rng, pad, envelope, props):
    """Adds `note` so the whole event JSON is about TARGET_EVENT_BYTES (performance-plan.md: about 1 KiB)."""
    probe = dict(envelope, properties=dict(props, note=""))
    need = max(TARGET_EVENT_BYTES - len(json.dumps(probe, separators=(",", ":")).encode()), 0)
    start = rng.randrange(0, len(pad) - need)
    return dict(props, note=pad[start:start + need])


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git_state():
    def g(*a):
        return run(["git", *a], check=False).stdout.strip()
    skip = ":(exclude)perf/results"
    h = hashlib.sha256(run(["git", "diff", "HEAD", "--", ".", skip], check=False).stdout.encode())
    for f in sorted(g("ls-files", "-o", "--exclude-standard").splitlines()):
        if f.startswith("perf/results/"):
            continue
        h.update(f.encode())
        try:
            h.update((ROOT / f).read_bytes())
        except OSError:
            pass
    return {"revision": g("rev-parse", "HEAD"), "branch": g("rev-parse", "--abbrev-ref", "HEAD"),
            "dirty": bool(g("status", "--porcelain", "--", ".", skip)), "dirty_diff_sha256": h.hexdigest()}


def percentile(sorted_vals, p):
    """Nearest-rank percentile of raw samples. Never average percentiles across runs."""
    if not sorted_vals:
        return None
    return sorted_vals[max(1, math.ceil(p / 100 * len(sorted_vals))) - 1]
