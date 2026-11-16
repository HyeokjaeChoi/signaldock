"""Tests for check-coverage.py (ADR-0049 decision 4). Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'

The ratchet cases build a throwaway git repository: a base commit (the ref `base`) and a staged change, which is what
the check reads. The cases that lower the value are the proof that the check fails on a bad sample.
"""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "check-coverage.py"
spec = importlib.util.spec_from_file_location("check_coverage", SCRIPT)
cc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cc)

BASE = {"sdk-android": {"line": 80.0}, "sdk-ios": {"line": 90.0}}


class Measure(unittest.TestCase):
    def test_band(self):
        self.assertEqual(cc.measure("sdk-ios", 90.5, BASE), [])
        self.assertEqual(cc.measure("sdk-ios", 89.5, BASE), [])
        self.assertEqual(len(cc.measure("sdk-ios", 89.4, BASE)), 1)

    def test_rise_prints_value_to_commit(self):
        msg = cc.measure("sdk-ios", 91.0, BASE)[0]
        self.assertIn("91.0", msg)
        self.assertIn("lock the rise", msg)


class Ratchet(unittest.TestCase):
    def test_same_or_higher_passes(self):
        self.assertEqual(cc.ratchet({"sdk-ios": {"line": 90.0}, "sdk-android": {"line": 85.0}}, BASE), [])

    def test_lowered_without_marker_fails(self):
        self.assertEqual(len(cc.ratchet({"sdk-ios": {"line": 89.0}, "sdk-android": {"line": 80.0}}, BASE)), 1)

    def test_lowered_with_marker_passes(self):
        now = {"sdk-ios": {"line": 89.0, "lowered": {"from": 90.0, "reason": "removed tested API"}}}
        self.assertEqual(cc.ratchet(now, BASE), [])

    def test_marker_must_name_base_value_and_reason(self):
        for low in ({"from": 91.0, "reason": "x"}, {"from": 90.0, "reason": " "}, {"from": 90.0}):
            self.assertEqual(len(cc.ratchet({"sdk-ios": {"line": 89.0, "lowered": low}}, BASE)), 1)

    def test_new_module_passes(self):
        self.assertEqual(cc.ratchet({"sdk-new": {"line": 1.0}}, BASE), [])


class Cli(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for a in (("init", "-q"), ("config", "user.email", "t@example.com"), ("config", "user.name", "t")):
            self.git(*a)
        self.write(BASE)
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.git("branch", "base")

    def git(self, *a):
        return subprocess.run(["git", *a], cwd=self.root, check=True, capture_output=True, text=True)

    def write(self, data):
        (self.root / "scripts").mkdir(exist_ok=True)
        (self.root / cc.FILE).write_text(json.dumps(data))
        self.git("add", cc.FILE)

    def ratchet(self):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), "ratchet", "base"], cwd=self.root, capture_output=True, text=True)

    def test_clean_passes(self):
        self.assertEqual(self.ratchet().returncode, 0)

    def test_bad_sample_fails(self):
        self.write({"sdk-android": {"line": 79.0}, "sdk-ios": {"line": 90.0}})
        r = self.ratchet()
        self.assertEqual(r.returncode, 1)
        self.assertIn("sdk-android", r.stderr)

    def test_marker_passes(self):
        self.write({"sdk-android": {"line": 79.0, "lowered": {"from": 80.0, "reason": "r"}}, "sdk-ios": {"line": 90.0}})
        self.assertEqual(self.ratchet().returncode, 0)


if __name__ == "__main__":
    unittest.main()
