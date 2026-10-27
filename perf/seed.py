#!/usr/bin/env python3
"""Builds a seed template database for the load measurement (performance-plan.md "Data and queries").

  python3 perf/seed.py build --size 100000 --dist recent70      # or: --dist uniform, --size 10000
  python3 perf/seed.py list

Fixed generator seed. Types are exactly 70/20/10 and spread over 100 installation IDs; time and type are drawn
independently. Rows are about 1 KiB as event JSON. received_at lies in [T-30d, T), T = Asia/Seoul midnight.
`uniform` spreads rows evenly over 30 days; `recent70` puts 70 % in the last 24 hours.
The result is the database `seed_<size>_<dist>`; run_one.py restores a run database from it with CREATE DATABASE ... TEMPLATE.
This tool writes only to test databases of the signaldock-perf project, never to the default stack.
"""
import argparse
import csv
import datetime as dt
import json
import random
import sys
import time
import urllib.request

import common as c

SEED = 1007  # generator seed, recorded in the seed metadata
DAY_MS = 86_400_000
# One column per CSV field, in order. V4 (ADR-0046) made sdk_platform NOT NULL without a default, so the COPY must name it.
COPY_COLUMNS = ("event_id", "installation_id", "name", "occurred_at", "sdk_version", "sdk_platform", "properties", "received_at")


def seed_name(size, dist):
    return f"seed_{size}_{dist}"


def meta_path(size, dist):
    return c.WORK / "seeds" / f"{seed_name(size, dist)}.json"


def metadata_is_valid(size, dist, day):
    """A seed database is reused only with complete metadata for the same size, distribution, and T."""
    try:
        meta = json.loads(meta_path(size, dist).read_text())
    except (OSError, UnicodeError, json.JSONDecodeError):
        return False
    required = {"name", "size", "dist", "generator_seed", "reference_time_T", "reference_time_T_utc", "csv_sha256", "csv_bytes",
                "event_json_bytes_min_avg_max", "name_counts", "installations", "table_bytes", "index_bytes", "database_bytes", "build_seconds"}
    return (isinstance(meta, dict) and required.issubset(meta) and meta["name"] == seed_name(size, dist) and meta["size"] == size
            and meta["dist"] == dist and meta["reference_time_T"] == c.reference_time(day).isoformat())


def generate(size, dist, T, out):
    rng = random.Random(f"{SEED}/{size}/{dist}")
    t_ms = int(T.timestamp() * 1000)
    if dist == "uniform":
        times = [t_ms - 30 * DAY_MS + rng.randrange(30 * DAY_MS) for _ in range(size)]
    elif dist == "recent70":
        recent = round(size * 0.7)
        times = [t_ms - DAY_MS + rng.randrange(DAY_MS) for _ in range(recent)]
        times += [t_ms - 30 * DAY_MS + rng.randrange(29 * DAY_MS) for _ in range(size - recent)]
    else:
        raise SystemExit(f"unknown dist {dist}")
    times.sort()  # physical insert order follows time, like real appends
    names = [n for n, w in zip(c.EVENT_NAMES, c.NAME_WEIGHTS) for _ in range(size * w // 100)]
    names += [c.EVENT_NAMES[0]] * (size - len(names))  # rounding remainder (none for round sizes)
    rng.shuffle(names)
    inst_ids = c.installation_ids()
    insts = [inst_ids[i % len(inst_ids)] for i in range(size)]
    rng.shuffle(insts)
    pad = c.pad_source(rng)
    counts = {n: 0 for n in c.EVENT_NAMES}
    sizes = []
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        for i in range(size):
            name = names[i]
            counts[name] += 1
            eid = f"{0x5eed0000 + (i >> 16):08x}-{i & 0xffff:04x}-4000-8000-{i:012x}"  # unique, valid UUIDv4 shape
            received = dt.datetime.fromtimestamp(times[i] / 1000, dt.timezone.utc)
            occurred = dt.datetime.fromtimestamp((times[i] - rng.randrange(5000)) / 1000, dt.timezone.utc)
            envelope = {"contractVersion": "1", "eventId": eid, "name": name, "occurredAt": c.iso_ms(occurred), "sdkVersion": "0.1.0"}
            props = c.make_properties(rng, name)
            props = c.with_note(rng, pad, envelope, props)
            sizes.append(len(json.dumps(dict(envelope, properties=props), separators=(",", ":")).encode()))
            w.writerow([eid, insts[i], name, c.iso_ms(occurred), "0.1.0", "android", json.dumps(props, separators=(",", ":")), c.iso_ms(received)])
    return counts, (min(sizes), sum(sizes) // len(sizes), max(sizes))


def ensure_extension(db):
    """pg_stat_statements lives in every template, so each restored `bench` database has it (ADR-0013). Idempotent."""
    c.psql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements", db=db)


def wait_health(timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{c.SERVER_PORT}/health", timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            time.sleep(1)
    raise RuntimeError("server not healthy")


def ensure_base_schema():
    """Flyway migrates through the real server, so the template has the product's own schema and history."""
    if c.psql("SELECT 1 FROM pg_database WHERE datname='base_schema'", db="postgres"):
        # An interrupted first build can leave an empty template; reuse it only when Flyway migrated it to the current schema (V4 added sdk_platform).
        # A template made before V4 has no sdk_platform column: rebuild it.
        if c.psql("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='events' AND column_name='sdk_platform')", db="base_schema") == "t":
            ensure_extension("base_schema")
            return
        c.log("base_schema exists but is not migrated; rebuilding it")
        c.psql("DROP DATABASE base_schema", db="postgres")
    c.log("building server image (first run takes a few minutes)")
    c.compose("build", "server")
    c.psql("CREATE DATABASE base_schema", db="postgres")
    c.compose("up", "-d", "--no-deps", "server", env={"SIGNALDOCK_DB_NAME": "base_schema"})
    wait_health()
    c.compose("rm", "-sf", "server")
    c.psql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='base_schema'", db="postgres")
    ensure_extension("base_schema")


def build(size, dist, day, force):
    c.WORK.joinpath("seeds").mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    c.compose("up", "-d", "--wait", "db")
    c.wait_db()
    name = seed_name(size, dist)
    exists = c.psql(f"SELECT 1 FROM pg_database WHERE datname='{name}'", db="postgres")
    if exists and not force:
        ensure_extension(name)
        if metadata_is_valid(size, dist, day):
            c.log(f"{name} exists; use --force to rebuild")
            return
        c.log(f"{name} exists but its metadata is missing, incomplete, or for another T; rebuilding")
    ensure_base_schema()
    T = c.reference_time(day)
    csv_path = c.WORK / "seeds" / f"{name}.csv"
    c.log(f"generating {size} rows ({dist}), T={T.isoformat()}")
    counts, esize = generate(size, dist, T, csv_path)
    c.log("loading")
    meta_path(size, dist).unlink(missing_ok=True)  # a failed rebuild must not leave metadata that marks it valid
    c.psql(f"DROP DATABASE IF EXISTS {name}", db="postgres")
    c.psql(f"CREATE DATABASE {name} TEMPLATE base_schema", db="postgres")
    with open(csv_path) as f:
        c.psql(f"COPY events ({', '.join(COPY_COLUMNS)}) FROM STDIN (FORMAT csv)", db=name, stdin=f)
    c.psql("VACUUM (ANALYZE) events", db=name)
    c.psql("CHECKPOINT", db=name)
    rows = int(c.psql("SELECT count(*) FROM events", db=name))
    assert rows == size, rows
    meta = {"name": name, "size": size, "dist": dist, "generator_seed": SEED, "reference_time_T": T.isoformat(),
            "reference_time_T_utc": c.iso_ms(T), "csv_sha256": c.sha256_file(csv_path), "csv_bytes": csv_path.stat().st_size,
            "event_json_bytes_min_avg_max": esize, "name_counts": counts, "installations": c.INSTALLATIONS,
            "table_bytes": int(c.psql("SELECT pg_table_size('events')", db=name)),
            "index_bytes": int(c.psql("SELECT pg_indexes_size('events')", db=name)),
            "database_bytes": int(c.psql(f"SELECT pg_database_size('{name}')", db=name)),
            "build_seconds": round(time.time() - t0, 1)}
    meta_path(size, dist).write_text(json.dumps(meta, indent=2) + "\n")
    c.log(f"done in {meta['build_seconds']}s: {meta}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--size", type=int, required=True)
    b.add_argument("--dist", choices=["uniform", "recent70"], required=True)
    b.add_argument("--day", help="Asia/Seoul date of T (default today); keep it the same for a whole campaign")
    b.add_argument("--force", action="store_true")
    sub.add_parser("list")
    a = p.parse_args()
    if a.cmd == "list":
        for m in sorted((c.WORK / "seeds").glob("*.json")):
            print(m.read_text())
    else:
        build(a.size, a.dist, a.day, a.force)
    return 0


if __name__ == "__main__":
    sys.exit(main())
