#!/usr/bin/env python3
"""Seed a SignalDock server with synthetic demo data (cloud-demo spec D4, ADR-0044 item 4).

Run: SIGNALDOCK_COLLECTION_KEY=... python3 -I scripts/demo-seed.py [--base-url URL] [--now ISO] [--dry-run]

The same --now and --seed give the same events. Before any other batch, the script sends one sentinel
event whose content depends only on --seed and the Seoul date of --now. If the server answers `duplicate`
or `rejected` with code CONFLICT for it, the database already holds a seed for that date: the script exits 1
and sends nothing else. Any other rejected code fails with that code. This guards a rerun on the same Seoul day without a reset; the D3 reset script owns the
reset (cloud-demo D3). A 503 TEMPORARILY_UNAVAILABLE or 429 stores nothing. Only REQUEST_TIMEOUT leaves an uncertain outcome, so
a `duplicate` is valid only after an earlier attempt failed with REQUEST_TIMEOUT. The data is synthetic.
HTTP 429 and 503 are retried (Retry-After, at most 5 attempts). The SDK version comes from Versions.kt.
"""
import argparse
import json
import os
import random
import re
import sys
import urllib.error
import urllib.request
import uuid
from time import sleep
from datetime import datetime, time, timedelta, timezone
from pathlib import Path

PLAN_PATH = Path(__file__).resolve().parent.parent / "tracking-plan" / "plan.json"
VERSIONS_PATH = (Path(__file__).resolve().parent.parent
                 / "sdk/sdk-android/src/main/kotlin/local/signaldock/internal/transport/Versions.kt")
OLD_SDK_VERSION = "0.2.0"
MAX_ATTEMPTS = 5
MAX_RETRY_WAIT = 30
SEOUL = timezone(timedelta(hours=9))  # Asia/Seoul has had no DST since 1988; avoids a tzdata dependency
SESSIONS_PER_DAY = [60, 50, 40, 32, 26, 20, 16]  # index 0 = today (Seoul); recent days have more
INSTALLATIONS = 40
VIOLATION_SHARE = 0.06
# Server limits (IngestRules.kt, ADR-0036). We stay below them.
MAX_EVENTS_PER_BATCH = 50
MAX_BATCH_BYTES = 200_000  # server limit is 262144
MAX_REPORT_BYTES = 4096
REPORT_REASONS = ["queue_full", "storage_error", "invalid_event", "event_too_large", "server_rejected"]
UNKNOWN_EVENT_NAME = "product_shared"  # not in the plan: planStatus unplanned_event


def sdk_version(path=VERSIONS_PATH):
    """The wire SDK_VERSION that scripts/check-repo-rules.sh compares with sdkVersion."""
    m = re.search(r'^internal const val SDK_VERSION = "([^"]+)"$', Path(path).read_text(), re.M)
    if not m:
        raise SystemExit(f"error: SDK_VERSION not found in {path}")
    return m.group(1)


def load_plan(path=PLAN_PATH):
    return {e["name"]: e["properties"] for e in json.loads(Path(path).read_text())["events"]}


def _uuid4(rng):
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


def _fmt(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def sentinel(now, seed):
    """One fixed event per (seed, Seoul date): the rerun guard. Same shape as generate() items."""
    day = now.astimezone(SEOUL).date()
    rng = random.Random(f"{seed}-sentinel-{day}")
    t = datetime.combine(day, time.min, SEOUL)
    ev = {"eventId": _uuid4(rng), "name": "product_viewed", "occurredAt": _fmt(t),
          "properties": {"product_id": "prd-001"}, "sdkVersion": sdk_version(), "contractVersion": "1"}
    return ("00000000-0000-4000-8000-000000000001", ev, None)


def _violate(rng, name, props, plan):
    """Return (name, props, kind) with one intended plan violation (ADR-0022 item 6)."""
    kind = rng.choice(["unknown_event", "missing_required", "wrong_type"])
    if kind == "unknown_event":
        return UNKNOWN_EVENT_NAME, props, kind
    props = dict(props)
    if kind == "missing_required":
        del props[rng.choice([p["key"] for p in plan[name] if p["required"]])]
    else:
        key = rng.choice(sorted(props))
        props[key] = 12345 if isinstance(props[key], str) else str(props[key])
    return name, props, kind


def generate(now, seed, plan):
    """Return [(installation_id, event, violation_kind_or_None)] sorted by time. now must be timezone-aware."""
    rng = random.Random(seed)
    version = sdk_version()
    installs = [_uuid4(rng) for _ in range(INSTALLATIONS)]
    today = now.astimezone(SEOUL).date()
    out = []
    for back, sessions in enumerate(SESSIONS_PER_DAY):
        start = datetime.combine(today - timedelta(days=back), time.min, SEOUL)
        end = min(start + timedelta(days=1), now)
        span = (end - start).total_seconds()
        if back == 0:  # today: only the part of the Seoul day that has passed
            sessions = max(1, round(sessions * (end - start).total_seconds() / 86400))
        for _ in range(sessions):
            t = start + timedelta(seconds=rng.random() * span * 0.98)
            inst = rng.choice(installs)
            pid = f"prd-{rng.randint(1, 30):03d}"
            price = rng.randint(5, 120) * 1000
            steps = [("product_viewed", {"product_id": pid, **({"price_minor": price} if rng.random() < 0.7 else {})})]
            if rng.random() < 0.5:
                qty = rng.randint(1, 3)
                steps.append(("add_to_cart", {"product_id": pid, "quantity": qty, "price_minor": price}))
                if rng.random() < 0.6:
                    steps.append(("checkout_started", {"item_count": qty, "total_minor": qty * price}))
                    if rng.random() < 0.7:
                        steps.append(("purchase_completed", {"order_id": f"ord-{rng.randint(1, 99999):05d}",
                                                             "item_count": qty, "total_minor": qty * price,
                                                             "is_simulated": True}))
            for name, props in steps:
                t = min(t + timedelta(seconds=rng.randint(3, 90)), end)
                violation = None
                if rng.random() < VIOLATION_SHARE:
                    name, props, violation = _violate(rng, name, props, plan)
                t = t.replace(microsecond=t.microsecond // 1000 * 1000)
                out.append((inst, {"eventId": _uuid4(rng), "name": name, "occurredAt": _fmt(t), "properties": props,
                                   "sdkVersion": version, "contractVersion": "1"}, violation))
    out.sort(key=lambda x: (x[1]["occurredAt"], x[1]["eventId"]))
    return out


def _size(body):
    return len(json.dumps(body, separators=(",", ":")).encode())


def batches(events):
    """Group by installation (a batch has one installationId), then split by count and encoded size."""
    by_inst = {}
    for inst, ev, _ in events:
        by_inst.setdefault(inst, []).append(ev)
    result = []
    for inst, evs in by_inst.items():
        cur = []
        for ev in evs:
            if cur and (len(cur) >= MAX_EVENTS_PER_BATCH
                        or _size({"installationId": inst, "events": cur + [ev]}) > MAX_BATCH_BYTES):
                result.append({"installationId": inst, "events": cur})
                cur = []
            cur.append(ev)
        result.append({"installationId": inst, "events": cur})
    return result


def client_reports(seed):
    """Client reports (ADR-0036). The wire has no timestamp: the server stamps receipt time."""
    rng = random.Random(seed + 1)
    reports = []
    for version, n in ((sdk_version(), 4), (OLD_SDK_VERSION, 2)):
        for _ in range(n):
            reasons = rng.sample(REPORT_REASONS, rng.randint(1, 3))
            reports.append({"sdkVersion": version,
                            "discardedEvents": [{"reason": r, "count": rng.randint(1, 12)} for r in reasons]})
    return reports


def post(base_url, path, key, body):
    req = urllib.request.Request(base_url.rstrip("/") + path, data=json.dumps(body, separators=(",", ":")).encode(),
                                 method="POST", headers={"Authorization": f"Bearer {key}",
                                                         "SignalDock-Contract-Version": "1",
                                                         "Content-Type": "application/json"})
    uncertain = False  # an earlier attempt failed with REQUEST_TIMEOUT: it may have been stored
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read(), uncertain
        except urllib.error.HTTPError as e:
            if e.code in (429, 503) and attempt < MAX_ATTEMPTS:
                try:
                    uncertain = uncertain or json.loads(e.read()).get("code") == "REQUEST_TIMEOUT"
                except (ValueError, AttributeError):  # missing or non-JSON body: unknown, not uncertain
                    pass
                ra = e.headers.get("Retry-After", "")
                sleep(min(int(ra) if ra.isdigit() else 2, MAX_RETRY_WAIT))
                continue
            raise SystemExit(f"error: POST {path} returned HTTP {e.code} after {attempt} attempt(s): "
                             f"{e.read()[:300].decode(errors='replace')}")
        except urllib.error.URLError as e:
            raise SystemExit(f"error: POST {path} failed: {e.reason}")
    raise SystemExit(f"error: POST {path} made no attempt")  # unreachable: MAX_ATTEMPTS >= 1


def main(argv=None):
    p = argparse.ArgumentParser(description="Seed a SignalDock server with synthetic demo data.")
    p.add_argument("--base-url", default="http://127.0.0.1:8080")
    p.add_argument("--now", help="ISO 8601 timestamp with UTC offset (default: current time)")
    p.add_argument("--seed", type=int, default=20261025)
    p.add_argument("--dry-run", action="store_true", help="print counts, send nothing")
    a = p.parse_args(argv)
    now = datetime.fromisoformat(a.now) if a.now else datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise SystemExit("error: --now needs a UTC offset, for example 2026-10-25T12:00:00+09:00")
    events = generate(now, a.seed, load_plan())
    sent = sentinel(now, a.seed)
    bs, reports = batches([sent]) + batches(events), client_reports(a.seed)
    events = [sent] + events
    print(f"events={len(events)} violations={sum(1 for e in events if e[2])} batches={len(bs)} "
          f"client_reports={len(reports)}")
    if a.dry_run:
        return 0
    key = os.environ.get("SIGNALDOCK_COLLECTION_KEY")
    if not key:
        raise SystemExit("error: set SIGNALDOCK_COLLECTION_KEY")
    totals = {"accepted": 0, "duplicate_after_retry": 0}
    for n, b in enumerate(bs):
        body, retried = post(a.base_url, "/v1/events:batch", key, b)
        results = json.loads(body)["results"]
        rejected = [r for r in results if r["status"] == "rejected"]
        if n == 0:
            if any(r.get("code") == "CONFLICT" for r in rejected) or (
                    not retried and any(r["status"] == "duplicate" for r in results)):
                raise SystemExit(f"error: the database already holds a seed for {now.astimezone(SEOUL).date()}; "
                                 "reset it before seeding (cloud-demo D3)")
            if rejected:
                raise SystemExit(f"error: sentinel rejected: {rejected[0].get('code')}")
        if rejected:
            hint = ("; reset the database before seeding (cloud-demo D3)"
                    if any(r.get("code") == "CONFLICT" for r in rejected) else "")
            raise SystemExit(f"error: server rejected events: {[(r.get('index'), r.get('code')) for r in rejected[:3]]}{hint}")
        dup = sum(1 for r in results if r["status"] == "duplicate")
        if dup and not retried:
            raise SystemExit(f"error: {dup} events already stored; reset the database before seeding (cloud-demo D3)")
        totals["duplicate_after_retry"] += dup
        totals["accepted"] += sum(1 for r in results if r["status"] == "accepted")
    for r in reports:
        post(a.base_url, "/v1/client-reports", key, r)
    print(f"done accepted={totals['accepted']} duplicate_after_retry={totals['duplicate_after_retry']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
