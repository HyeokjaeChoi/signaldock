#!/usr/bin/env python3
"""Delivery failure E2E cases (issue 21, ADR-0010). Python 3 standard library only.

Run through scripts/e2e-delivery.sh. Expects: the Compose stack up, a booted emulator with the
consumer app and the demo app installed (both built for the fault proxy endpoint), and the
entry script's environment (it sources .env and exports E2E_*_PORT, SIGNALDOCK_*). Do not run
this file directly. Cases: 1 normal E2E (consumer app and demo app), 2 ACK loss, 3 partial
rejection, 4 content conflict, 5 client report (SDK drop count to the Dashboard query). Unknown case numbers exit nonzero before anything runs. Prints evidence for
each case and a PASS/FAIL summary; the exit code is 1 when any case failed. When E2E_RESULT_JSON names a file,
it also writes [{"name", "result": "pass"|"fail", "seconds"}] there (input of scripts/nightly-summary.py, ADR-0049 gate Q2).
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PKG = "local.signaldock.consumer"
DEMO_PKG = "local.signaldock.demo"
SERVER = f"http://127.0.0.1:{os.environ.get('E2E_SERVER_PORT', '8080')}"
DASHBOARD = f"http://127.0.0.1:{os.environ.get('E2E_DASHBOARD_PORT', '5173')}"  # nginx: adds the query key, GET only
PROXY_PORT = int(os.environ.get("E2E_PROXY_PORT", "8081"))
PROXY = f"http://127.0.0.1:{PROXY_PORT}"
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")), "platform-tools", "adb")


def say(msg=""):
    print(msg, flush=True)


# The entry script sourced .env and exported these (shell quoting already resolved).
ENV = os.environ


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, cwd=ROOT, **kw).stdout


def adb(*args):
    return run([ADB, *args])


def sql(query):
    out = run(["docker", "compose", "exec", "-T", "db", "psql", "-U", ENV["SIGNALDOCK_DB_USER"],
               "-d", ENV.get("SIGNALDOCK_DB_NAME", "signaldock"), "-tA", "-F", "|", "-c", query])
    return [line for line in out.splitlines() if line]


def http(method, url, headers=None, body=None):
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def dashboard(path):
    """What the Dashboard reads: the nginx proxy with the contract header."""
    return http("GET", DASHBOARD + path, {"SignalDock-Contract-Version": "1"})


def window():
    now = datetime.now(timezone.utc)
    f = lambda d: d.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    return f"from={f(now - timedelta(days=1))}&to={f(now + timedelta(days=1))}"


def name_count(name):
    status, body = dashboard(f"/v1/event-counts/by-name?{window()}&name={name}")
    assert status == 200, (status, body)
    return sum(i["count"] for i in body["items"] if i["name"] == name)


def event_ids():
    return set(sql("select event_id from events"))


def row(event_id):
    r = sql(f"select name, received_at, installation_id, properties::text from events where event_id='{event_id}'")
    return tuple(r[0].split("|", 3)) if r else None


# ---- proxy control and log ----
def proxy_mode(mode):
    status, _ = http("POST", f"{PROXY}/__fault/mode/{mode}", body=b"")
    assert status == 200, f"proxy mode {mode}: {status}"


def proxy_log():
    return http("GET", f"{PROXY}/__fault/log")[1]


# ---- device driving through the UI (the app has two buttons) ----
def ui_nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    return [n.attrib for n in ET.fromstring(xml[xml.index("<"):]).iter("node")]


def button_xy(label):
    for n in ui_nodes():
        if n.get("text", "").lower() == label.lower():
            l, t, r, b = map(int, re.findall(r"\d+", n["bounds"]))
            return str((l + r) // 2), str((t + b) // 2)
    raise RuntimeError(f"button not found: {label}")


def tap(label):
    x, y = button_xy(label)
    adb("shell", "input", "tap", x, y)


def screen_text(prefix, timeout=20, not_equal=None):
    end = time.time() + timeout
    while time.time() < end:
        for n in ui_nodes():
            t = n.get("text", "")
            if t.startswith(prefix) and t != not_equal:
                return t
        time.sleep(1)
    raise RuntimeError(f"no screen text starting with {prefix!r}")


def fresh_app():
    adb("shell", "pm", "clear", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    screen_text("diagnostics:")


def track_once(previous):
    tap("Track consumer_ping")
    t = screen_text("track:", not_equal=previous)
    m = re.search(r"eventId=([0-9a-f-]{36})", t)
    assert m, t
    return m.group(1), t


def diagnostics():
    """Parsed `diagnostics()` snapshot of the device queue, read from the screen."""
    tap("Show diagnostics")
    time.sleep(1)
    text = re.sub(r"\s+", " ", screen_text("diagnostics:"))
    d = {"raw": text, "queued": int(re.search(r"queuedEvents=(\d+)", text).group(1)),
         "failures": int(re.search(r"retryFailureCount=(\d+)", text).group(1))}
    ack = re.search(r"lastAck=AckDiagnostic\(atEpochMillis=\d+, accepted=(\d+), duplicate=(\d+), rejected=(\d+)\)", text)
    d["ack"] = tuple(map(int, ack.groups())) if ack else None  # (accepted, duplicate, rejected)
    err = re.search(r"lastDeliveryError=DeliveryErrorDiagnostic\(kind=(\w+)", text)
    d["error"] = err.group(1) if err else None
    d["rejections"] = re.findall(r"RejectionDiagnostic\(eventId=([0-9a-f-]+), code=(\w+),", text)
    return d


def wait_for(what, fn, timeout):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(3)
    raise AssertionError(f"timeout waiting for {what}")


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)
    say(f"  ok: {msg}")


# ---- cases ----
def case1_normal():
    proxy_mode("pass")
    c0, ids0 = name_count("consumer_ping"), event_ids()
    fresh_app()
    eid, text = track_once("")
    say(f"  track on device: {text.splitlines()[0]}")
    d = wait_for("ACK on device", lambda: (lambda x: x if x["queued"] == 0 and x["ack"] else None)(diagnostics()), 120)
    say(f"  device diagnostics: queued={d['queued']} lastAck(accepted,duplicate,rejected)={d['ack']}")
    check(d["ack"] == (1, 0, 0), "device lastAck is accepted=1")
    check(event_ids() - ids0 == {eid}, f"server stored exactly the new eventId {eid}")
    status, detail = dashboard(f"/v1/events/{eid}")
    say(f"  Dashboard GET /v1/events/{eid}: {status} name={detail.get('name')} receivedAt={detail.get('receivedAt')}")
    check(status == 200 and detail["name"] == "consumer_ping", "Dashboard detail shows the event")
    status, page = dashboard(f"/v1/events?{window()}&eventId={eid}")
    check(status == 200 and [i["eventId"] for i in page["items"]] == [eid], "Dashboard list (eventId filter) shows the event")
    check(name_count("consumer_ping") == c0 + 1, f"Dashboard by-name consumer_ping {c0} -> {c0 + 1}")
    case1_demo()


def tap_node(match):
    """Tap the center of the first UI node for which match(attrib) is true."""
    for n in ui_nodes():
        if match(n):
            l, t, r, b = map(int, re.findall(r"\d+", n["bounds"]))
            adb("shell", "input", "tap", str((l + r) // 2), str((t + b) // 2))
            return
    raise RuntimeError("UI node not found")


def case1_demo():
    """Spec section 7 'Normal E2E' names the demo app: product -> add to cart through its Compose UI."""
    say("  -- demo app (sdk/demo/app, debug build): product -> Add to cart --")
    pv0, atc0, ids0 = name_count("product_viewed"), name_count("add_to_cart"), event_ids()
    adb("shell", "pm", "clear", DEMO_PKG)
    adb("shell", "am", "start", "-n", f"{DEMO_PKG}/local.signaldock.demo.app.MainActivity")
    screen_text("Products")
    tap_node(lambda n: n.get("text", "").startswith("KRW"))  # first product card
    viewed = re.search(r"product_viewed \(([0-9a-f]{8})\)", screen_text("Saved on device: product_viewed")).group(1)
    tap_node(lambda n: n.get("text") == "Add to cart")
    added = re.search(r"add_to_cart \(([0-9a-f]{8})\)", screen_text("Saved on device: add_to_cart")).group(1)
    say(f"  demo track on device: product_viewed ({viewed}), add_to_cart ({added}) (the screen shows 8 characters of each eventId)")

    def new_with_prefix():
        new = event_ids() - ids0
        found = {p: [e for e in new if e.startswith(p)] for p in (viewed, added)}
        return found if all(len(v) == 1 for v in found.values()) else None

    found = wait_for("demo events on the server", new_with_prefix, 120)
    vid, aid = found[viewed][0], found[added][0]
    say(f"  server stored the demo events: product_viewed={vid} add_to_cart={aid}")
    check(event_ids() - ids0 == {vid, aid}, "server stored exactly the two new demo eventIds")
    for eid, name in ((vid, "product_viewed"), (aid, "add_to_cart")):
        status, detail = dashboard(f"/v1/events/{eid}")
        say(f"  Dashboard GET /v1/events/{eid}: {status} name={detail.get('name')} receivedAt={detail.get('receivedAt')}")
        check(status == 200 and detail["name"] == name, f"Dashboard detail shows the demo {name} event")
    check(name_count("product_viewed") == pv0 + 1 and name_count("add_to_cart") == atc0 + 1,
          f"Dashboard by-name product_viewed {pv0} -> {pv0 + 1}, add_to_cart {atc0} -> {atc0 + 1}")


def case2_ack_loss():
    proxy_mode("pass")
    ids0, c0 = event_ids(), name_count("consumer_ping")
    before_log = len(proxy_log())
    proxy_mode("drop-response")
    fresh_app()
    eid, _ = track_once("")
    say(f"  tracked eventId={eid}; proxy mode drop-response; server rows before={len(ids0)}, consumer_ping count before={c0}")
    wait_for("server commit of the event", lambda: row(eid), 90)
    ids1, c1 = event_ids(), name_count("consumer_ping")
    received1 = row(eid)[1]
    say(f"  server committed: rows={len(ids1)} count={c1} receivedAt={received1}")
    check(ids1 - ids0 == {eid} and c1 == c0 + 1, "server has exactly one new row and count +1 after the first (lost) response")
    d = wait_for("NETWORK failure on device", lambda: (lambda x: x if x["error"] == "NETWORK" else None)(diagnostics()), 60)
    say(f"  device after lost ACK: queued={d['queued']} lastDeliveryError={d['error']} retryFailureCount={d['failures']} lastAck={d['ack']}")
    check(d["queued"] == 1 and d["ack"] is None, "device keeps the event in the queue (no ACK applied)")
    # Let the SDK resend at least once more while the response is still lost.
    wait_for("a resend while the ACK is still lost", lambda: len([e for e in proxy_log()[before_log:] if eid in e.get("eventIds", [])]) >= 2, 120)
    mine = [e for e in proxy_log()[before_log:] if eid in e.get("eventIds", [])]
    for e in mine:
        say(f"  proxy: action={e['action']} upstreamStatus={e['upstreamStatus']} upstream={e['upstreamBody'][:120]}")
    check(row(eid)[1] == received1 and len(event_ids()) == len(ids1), "resends during the outage added no row and kept receivedAt")
    proxy_mode("pass")
    say("  proxy mode pass; waiting for the SDK to resend and apply the ACK")
    d = wait_for("ACK applied on device", lambda: (lambda x: x if x["queued"] == 0 else None)(diagnostics()), 400)
    say(f"  device after recovery: queued={d['queued']} lastAck(accepted,duplicate,rejected)={d['ack']} retryFailureCount={d['failures']}")
    check(d["ack"] == (0, 1, 0), "device ACK is duplicate=1 (server answered the resend; queue item removed)")
    check(d["failures"] == 0, "retry failure count reset after the valid ACK")
    ids2, c2 = event_ids(), name_count("consumer_ping")
    say(f"  eventIds: before={len(ids0)} afterCommit={len(ids1)} afterResend={len(ids2)}; same set after resend as after commit: {ids2 == ids1}")
    check(ids2 == ids1, "event ID set unchanged by the resend")
    check(c2 == c1 and len(ids2) == len(ids1), f"unique rows {len(ids1)} -> {len(ids2)} and Dashboard aggregate {c1} -> {c2} did not increase")
    check(row(eid)[1] == received1, "first receivedAt unchanged")
    status, detail = dashboard(f"/v1/events/{eid}")
    check(status == 200 and detail["receivedAt"].startswith(received1[:19].replace(" ", "T")), "Dashboard detail still shows the first receivedAt")


def airplane(on):
    """Airplane mode on or off; waits until the device has no default network (on) or has one (off)."""
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if on else "disable")

    def settled():
        for l in adb("shell", "dumpsys", "connectivity").splitlines():
            if l.startswith("Active default network:"):
                return (l.split(":", 1)[1].strip() == "none") == on
        return False

    wait_for(f"airplane mode {'on' if on else 'off'}", settled, 30 if on else 60)


def burst_events(n):
    """Fresh app start that makes n events with n track() calls in one coroutine (consumer app `--ei burst n`,
    ADR-0020 item 2; the same hook as scripts/host-cost-measure.sh). Waits for its `burst_done` log line.
    No UI tap: a bursted tap on a loaded emulator can give no click (ADR-0010 amendment 2026-11-16).
    The device is offline (airplane mode) during the burst: after `pm clear`, init enqueues a DeliveryWorker
    with no delay, and it could send the first event alone before the rest are stored. Offline, no send starts
    before all n events are stored. Network is back on after `burst_done` (ADR-0010 amendment 2026-11-17)."""
    adb("logcat", "-c")
    adb("shell", "pm", "clear", PKG)
    try:
        airplane(True)  # inside try: a timeout here must not leave the device offline for the next case
        _burst(n)
    finally:
        airplane(False)


def _burst(n):
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ei", "burst", str(n))

    def done():
        lines = [l for l in adb("logcat", "-d", "-s", "SignalDockHostCost").splitlines() if re.search(r"burst_(done|failed)", l)]
        return lines[-1] if lines else None

    try:
        line = wait_for(f"burst_done n={n}", done, 60)
    except AssertionError as e:
        # An init failure shows on screen ("init failed: ...") but is not logged.
        try:
            texts = [t for t in (x.get("text", "") for x in ui_nodes()) if t.startswith(("init failed", "track:", "diagnostics:"))]
        except Exception:
            raise e
        raise AssertionError(f"{e}; screen: {texts}") from e
    if f"burst_done n={n}" not in line:
        raise AssertionError(f"track() failed during the burst: {line}")


def mutated_batch():
    """Makes 3 events with one track() burst (burst_events) in proxy mode mutate: the proxy corrupts event index 1 of the
    batch, so the server rejects it. The burst runs offline (burst_events), so no send starts before all 3 events are
    stored and one batch of 3 is guaranteed. Fails at once, with no retry, when the 3 events are not in exactly one
    batch of exactly 3 eventIds (ADR-0049 decision 5).
    Returns (device diagnostics after the ACK, batch eventIds, rejected eventId, proxy log entry)."""
    proxy_mode("pass")
    before_log = len(proxy_log())
    proxy_mode("mutate")
    burst_events(3)
    say("  3 events from one track() burst (consumer app --ei burst 3, ADR-0020 hook); the batch must hold exactly 3 events (proxy mode mutate: event index 1 of the batch gets a leading space in its name)")
    d = wait_for("ACK on device", lambda: (lambda x: x if x["queued"] == 0 and x["ack"] else None)(diagnostics()), 120)
    proxy_mode("pass")
    batches = [e for e in proxy_log()[before_log:] if e.get("mutatedEventId")]
    sent_ids = batches[0]["eventIds"] if batches else []
    if len(batches) != 1 or len(sent_ids) != 3:
        raise AssertionError(f"the burst events were not in one batch of exactly 3 events: {[e.get('eventIds') for e in proxy_log()[before_log:]]}")
    check(True, f"the burst events went out in one batch: {sent_ids}")
    return d, sent_ids, batches[0]["mutatedEventId"], batches[0]


def case3_partial():
    ids0 = event_ids()
    d, sent_ids, bad, batch = mutated_batch()
    say(f"  proxy: upstream status {batch['upstreamStatus']} body={batch['upstreamBody']}")
    say(f"  device diagnostics: queued={d['queued']} lastAck(accepted,duplicate,rejected)={d['ack']} recentRejections={d['rejections']} retryFailureCount={d['failures']}")
    check(len(sent_ids) == 3 and bad == sent_ids[1], f"three events in the batch; rejected eventId is {bad} (index 1)")
    new = event_ids() - ids0
    check(new == {sent_ids[0], sent_ids[2]}, f"server stored the two valid events {sorted(new)} and not {bad}")
    check(d["ack"] == (2, 0, 1), "device lastAck is accepted=2 rejected=1")
    check(d["rejections"] == [(bad, "INVALID_TEXT")], "device recentRejections lists only the invalid eventId with its code")
    check(d["queued"] == 0 and d["failures"] == 0, "device queue is empty and the failure count is 0")


def dropped_count(reason):
    """Sum of the client report counts for one reason, as the Dashboard 'Dropped events' card reads them."""
    status, body = dashboard(f"/v1/client-report-counts?{window()}")
    assert status == 200, (status, body)
    return sum(i["count"] for i in body["items"] if i["reason"] == reason)


def reports_after(t):
    return [e for e in proxy_log() if e["path"] == "/v1/client-reports" and e["t"] > t]


def settle_reports():
    """Wait until the last rejecting batch (case 3) has its report, so a late report cannot count for case 5."""
    def done():
        log = proxy_log()
        mutated = [e["t"] for e in log if e.get("mutatedEventId")]
        return not mutated or bool(reports_after(mutated[-1]))
    wait_for("earlier client reports to settle", done, 120)


def case5_client_report():
    """ADR-0036 client reports: a counted drop on the device reaches the Dashboard query.
    The drop is server_rejected (the SDK counts each per-item rejection in the ACK), caused as in case 3."""
    proxy_mode("pass")
    settle_reports()
    r0 = dropped_count("server_rejected")
    rows0 = int(sql("select count(*) from client_reports")[0])
    say(f"  before: Dashboard client-report-counts server_rejected={r0}, client_reports rows={rows0}")
    d, sent_ids, bad, batch = mutated_batch()
    # The SDK sends the report after the batch of the same send cycle (a few seconds, no extra event).
    mine = wait_for("client report of this batch", lambda: reports_after(batch["t"]), 120)
    wait_for("client report on the server", lambda: dropped_count("server_rejected") > r0, 30)
    r1 = dropped_count("server_rejected")
    status, body = dashboard(f"/v1/client-report-counts?{window()}")
    say(f"  Dashboard GET /v1/client-report-counts: {status} {json.dumps(body)}")
    check(len(mine) == 1 and mine[0]["upstreamStatus"] == 204, f"proxy saw one POST /v1/client-reports after the batch, answered {[m['upstreamStatus'] for m in mine]}")
    check(r1 == r0 + 1, f"Dashboard server_rejected {r0} -> {r1} (exactly one rejected event)")
    check(int(sql("select count(*) from client_reports")[0]) == rows0 + 1, "server stored exactly one new client_reports row")


def case4_conflict():
    key = ENV["SIGNALDOCK_COLLECTION_KEY"]
    run_id = uuid.uuid4().hex[:8]
    name = f"e2e_conflict_{run_id}"
    inst, eid, other = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())

    def ev(event_id, n, props):
        return {"contractVersion": "1", "eventId": event_id, "name": n, "occurredAt": "2026-10-12T00:00:00.123Z",
                "properties": props, "sdkVersion": "0.1.0"}

    def post(events):
        body = json.dumps({"installationId": inst, "events": events}).encode()
        s, r = http("POST", f"{SERVER}/v1/events:batch", {"Authorization": f"Bearer {key}", "SignalDock-Contract-Version": "1",
                                                           "Content-Type": "application/json"}, body)
        assert s == 200, (s, r)
        return [(i["status"], i.get("code")) for i in r["results"]]

    ids0 = event_ids()
    n0, c0 = name_count(name), name_count(name + "_other")
    r = post([ev(eid, name, {"price": 100, "color": "red"})])
    say(f"  first send {eid}: {r}")
    check(r == [("accepted", None)], "original stored as accepted")
    orig = row(eid)
    say(f"  original row: {orig}")
    time.sleep(1.1)  # a changed receivedAt would be visible at ms precision
    r = post([ev(eid, name, {"color": "red", "price": 100.0})])
    check(r == [("duplicate", None)], "same ID, same content (key order, 100 vs 100.0) is duplicate")
    r = post([ev(eid, name + "_other", {"price": 100, "color": "red"}), ev(other, name, {"price": 1})])
    say(f"  conflicting send (same eventId, different name) + a new valid event in one batch: {r}")
    check(r == [("rejected", "CONFLICT"), ("accepted", None)], "different content is rejected as CONFLICT; the valid sibling is accepted")
    r = post([ev(eid, name, {"price": 101, "color": "red"})])
    check(r == [("rejected", "CONFLICT")], "different properties are rejected as CONFLICT")
    check(row(eid) == orig, "original row (name, receivedAt, installationId, properties) unchanged")
    status, detail = dashboard(f"/v1/events/{eid}")
    check(status == 200 and detail["name"] == name and detail["properties"] == {"price": 100, "color": "red"}, "Dashboard detail shows the original content")
    check(event_ids() - ids0 == {eid, other}, "rows added: the original and the valid sibling only")
    check(name_count(name) == n0 + 2 and name_count(name + "_other") == c0, f"aggregate: {name} +2 (original + sibling) from {n0}, conflicting name unchanged at {c0}")


CASES = [("1 normal E2E", case1_normal), ("2 ACK loss", case2_ack_loss),
         ("3 partial rejection", case3_partial), ("4 content conflict", case4_conflict),
         ("5 client report", case5_client_report)]


def environment():
    say("=== environment ===")
    say(f"date: {datetime.now(timezone.utc).isoformat(timespec='seconds')}")
    say(f"git: {run(['git', 'rev-parse', 'HEAD']).strip()} dirty={bool(run(['git', 'status', '--porcelain']).strip())}")
    for prop in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abi", "ro.build.fingerprint"):
        say(f"emulator {prop}: {adb('shell', 'getprop', prop).strip()}")
    say(f"consumer app: {PKG} release (R8); demo app: {DEMO_PKG} debug; endpoint via fault proxy {PROXY} -> {SERVER}")
    say(f"db: {sql('select version()')[0]}")
    say(f"docker: {run(['docker', 'version', '--format', '{{.Server.Version}}']).strip()}")
    say(f"compose images: {' '.join(run(['docker', 'compose', 'images', '--format', 'table']).split())[:300]}")
    say(f"python: {sys.version.split()[0]}")


def main():
    only = set(sys.argv[1:])
    known = {title.split()[0] for title, _ in CASES}
    if only - known:
        say(f"error: unknown case number(s) {sorted(only - known)}; known: {sorted(known)}")
        sys.exit(2)
    environment()
    results = []
    for title, fn in CASES:
        if only and title.split()[0] not in only:
            continue
        say(f"\n=== case {title} ===")
        t0 = time.monotonic()
        try:
            fn()
            results.append((title, "PASS", "", time.monotonic() - t0))
        except Exception as e:  # keep running the other cases
            say(f"  FAIL: {e!r}")
            results.append((title, "FAIL", repr(e), time.monotonic() - t0))
    say("\n=== summary ===")
    for title, status, why, secs in results:
        say(f"{status}  case {title} {why} ({secs:.0f} s)")
    if ENV.get("E2E_RESULT_JSON"):
        with open(ENV["E2E_RESULT_JSON"], "w", encoding="utf-8") as f:
            json.dump([{"name": t, "result": s.lower(), "seconds": round(secs, 1)} for t, s, _, secs in results], f)
    sys.exit(0 if all(s == "PASS" for _, s, _, _ in results) else 1)


if __name__ == "__main__":
    main()
