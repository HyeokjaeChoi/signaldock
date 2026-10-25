"""Tests for demo-seed.py. Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import json
import os
import threading
import unittest
import unittest.mock
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("demo_seed", HERE / "demo-seed.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)

NOW = datetime(2026, 10, 25, 12, 0, tzinfo=timezone(timedelta(hours=9)))
PLAN = seed.load_plan()
TYPES = {"string": str, "boolean": bool, "integer": int, "number": (int, float)}


def plan_status(name, props):
    """ADR-0022 item 6."""
    if name not in PLAN:
        return "unplanned_event"
    plan = {p["key"]: p for p in PLAN[name]}
    ok = all(p["key"] in props for p in PLAN[name] if p["required"]) and all(
        k in plan and isinstance(v, TYPES[plan[k]["type"]]) and not (plan[k]["type"] != "boolean" and isinstance(v, bool))
        for k, v in props.items())
    return "planned" if ok else "invalid_properties"


class DemoSeedTest(unittest.TestCase):
    def setUp(self):
        self.events = seed.generate(NOW, 1, PLAN)

    def test_deterministic(self):
        self.assertEqual(self.events, seed.generate(NOW, 1, PLAN))
        self.assertNotEqual(self.events, seed.generate(NOW, 2, PLAN))

    def test_plan_status_matches_intent(self):
        kinds = set()
        for _, ev, violation in self.events:
            status = plan_status(ev["name"], ev["properties"])
            self.assertEqual(status == "planned", violation is None, ev)
            kinds.add(violation)
        self.assertEqual(kinds, {None, "unknown_event", "missing_required", "wrong_type"})
        share = sum(1 for e in self.events if e[2]) / len(self.events)
        self.assertGreater(share, 0.02)
        self.assertLess(share, 0.12)

    def test_seven_days_seoul_recent_heavier(self):
        seoul = timezone(timedelta(hours=9))
        counts = {}
        for _, ev, _ in self.events:
            t = datetime.strptime(ev["occurredAt"], "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
            self.assertLessEqual(t, NOW)
            counts[t.astimezone(seoul).date()] = counts.get(t.astimezone(seoul).date(), 0) + 1
        days = sorted(counts)
        self.assertEqual(len(days), 7)
        self.assertEqual(days[-1], NOW.date())
        self.assertEqual(days[0], NOW.date() - timedelta(days=6))
        self.assertGreater(counts[days[-2]], counts[days[0]])

    def test_batches_within_limits(self):
        bs = seed.batches(self.events)
        self.assertEqual(sum(len(b["events"]) for b in bs), len(self.events))
        for b in bs:
            self.assertLessEqual(len(b["events"]), 50)
            self.assertLess(len(json.dumps(b, separators=(",", ":")).encode()), 262144)

    def test_batch_splits_by_size(self):
        big = [("i", {"eventId": str(n), "properties": {"k": "x" * 15000}}, None) for n in range(40)]
        bs = seed.batches(big)
        self.assertGreater(len(bs), 1)
        for b in bs:
            self.assertLessEqual(seed._size(b), seed.MAX_BATCH_BYTES)

    def test_client_reports(self):
        reports = seed.client_reports(1)
        self.assertEqual(reports, seed.client_reports(1))
        for r in reports:
            self.assertLess(len(json.dumps(r).encode()), seed.MAX_REPORT_BYTES)
            reasons = [d["reason"] for d in r["discardedEvents"]]
            self.assertEqual(len(reasons), len(set(reasons)))
            self.assertLessEqual(set(reasons), set(seed.REPORT_REASONS))

    def test_today_scales_with_now(self):
        def today_events(h):
            now = datetime(2026, 10, 25, h, 0, tzinfo=timezone(timedelta(hours=9)))
            return sum(1 for _, ev, _ in seed.generate(now, 1, PLAN) if ev["occurredAt"] >= "2026-10-24T15:00")
        self.assertLess(today_events(6) * 2, today_events(18))

    def test_sdk_version_read_from_repo(self):
        v = seed.sdk_version()
        self.assertRegex(v, r"^\d+\.\d+\.\d+")
        self.assertIn(f'SDK_VERSION = "{v}"', seed.VERSIONS_PATH.read_text())
        self.assertTrue(all(e["sdkVersion"] == v for _, e, _ in self.events))
        with self.assertRaises(SystemExit):
            seed.sdk_version(HERE / "demo-seed.py")


class SendTest(unittest.TestCase):
    """main() against a local fake server. responses: list of (status, headers, (index, status[, code]) or None
    for 200; a dict for a non-200 JSON body)."""

    def run_main(self, responses):
        calls = []

        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append(self.path)
                code, headers, dup = responses.pop(0) if len(responses) > 1 else responses[0]
                out = b"{}"
                if isinstance(dup, dict):
                    out = json.dumps(dup).encode()
                elif self.path.endswith(":batch") and code == 200:
                    out = json.dumps({"results": [
                        {"index": i, "status": dup[1], **({"code": dup[2]} if len(dup) > 2 else {})}
                        if dup and i == dup[0] else {"index": i, "status": "accepted"}
                        for i in range(len(body["events"]))]}).encode()
                self.send_response(code)
                for k, v in headers.items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass

        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            with unittest.mock.patch.dict(os.environ, {"SIGNALDOCK_COLLECTION_KEY": "k"}), \
                    unittest.mock.patch.object(seed, "sleep") as slept, unittest.mock.patch("builtins.print"):
                code = None
                try:
                    code = seed.main(["--base-url", f"http://127.0.0.1:{srv.server_port}", "--now", NOW.isoformat()])
                except SystemExit as e:
                    code = e.code
        finally:
            srv.shutdown()
            srv.server_close()
        return code, calls, slept

    def test_all_accepted(self):
        code, _, _ = self.run_main([(200, {}, None)])
        self.assertEqual(code, 0)

    def test_sentinel_duplicate_stops_everything(self):
        code, calls, _ = self.run_main([(200, {}, (0, "duplicate"))])
        self.assertIn("already holds a seed for 2026-10-25; reset it before seeding (cloud-demo D3)", str(code))
        self.assertEqual(len(calls), 1)

    def test_sentinel_rejected_conflict_is_already_seeded(self):
        code, calls, _ = self.run_main([(200, {}, (0, "rejected", "CONFLICT"))])
        self.assertIn("already holds a seed", str(code))
        self.assertEqual(len(calls), 1)

    def test_sentinel_rejected_other_code_fails_with_code(self):
        code, calls, _ = self.run_main([(200, {}, (0, "rejected", "COLLECTION_BLOCKED"))])
        self.assertIn("sentinel rejected: COLLECTION_BLOCKED", str(code))
        self.assertNotIn("already holds", str(code))
        self.assertEqual(len(calls), 1)

    def test_later_rejected_prints_code(self):
        code, _, _ = self.run_main([(200, {}, None), (200, {}, (0, "rejected", "INVALID_EVENT"))])
        self.assertIn("INVALID_EVENT", str(code))

    def test_later_rejected_conflict_points_to_reset(self):
        code, _, _ = self.run_main([(200, {}, None), (200, {}, (0, "rejected", "CONFLICT"))])
        self.assertIn("reset the database before seeding (cloud-demo D3)", str(code))

    def test_later_duplicate_stops(self):
        code, _, _ = self.run_main([(200, {}, None), (200, {}, (0, "duplicate"))])
        self.assertIn("events already stored; reset the database before seeding (cloud-demo D3)", str(code))

    def test_sentinel_duplicate_after_unavailable_503_stops(self):
        code, calls, _ = self.run_main([(503, {"Retry-After": "0"}, {"code": "TEMPORARILY_UNAVAILABLE"}),
                                        (200, {}, (0, "duplicate"))])
        self.assertIn("already holds a seed", str(code))
        self.assertEqual(len(calls), 2)

    def test_duplicate_after_503_without_json_body_stops(self):
        code, _, _ = self.run_main([(503, {"Retry-After": "0"}, None), (200, {}, (0, "duplicate"))])
        self.assertIn("already holds a seed", str(code))

    def test_duplicate_after_request_timeout_ok(self):
        code, _, _ = self.run_main([(200, {}, None), (503, {"Retry-After": "0"}, {"code": "REQUEST_TIMEOUT"}),
                                    (200, {}, (0, "duplicate")), (200, {}, None)])
        self.assertEqual(code, 0)

    def test_sentinel_deterministic(self):
        self.assertEqual(seed.sentinel(NOW, 1), seed.sentinel(NOW, 1))
        self.assertNotEqual(seed.sentinel(NOW, 1), seed.sentinel(NOW, 2))
        self.assertEqual(seed.sentinel(NOW, 1)[1]["occurredAt"], "2026-10-24T15:00:00.000Z")

    def test_retry_503_then_ok(self):
        code, calls, slept = self.run_main([(503, {"Retry-After": "0"}, None), (200, {}, None)])
        self.assertEqual(code, 0)
        slept.assert_called_with(0)
        self.assertEqual(calls[0], calls[1])

    def test_401_fails_without_retry(self):
        code, calls, slept = self.run_main([(401, {}, None)])
        self.assertIn("HTTP 401", str(code))
        self.assertEqual(len(calls), 1)
        slept.assert_not_called()


if __name__ == "__main__":
    unittest.main()
