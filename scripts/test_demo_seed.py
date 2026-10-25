"""Tests for demo-seed.py. Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import json
import unittest
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
        self.assertTrue(0.02 < share < 0.12, share)

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
            self.assertTrue(set(reasons) <= set(seed.REPORT_REASONS))


if __name__ == "__main__":
    unittest.main()
