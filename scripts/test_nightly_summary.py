"""Tests for nightly-summary.py. Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("nightly_summary", HERE / "nightly-summary.py")
ns = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ns)

ENV = {"GITHUB_SHA": "abc", "GITHUB_RUN_ID": "7", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_EVENT_NAME": "schedule",
       "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "o/r"}
PASS = {"name": "1 normal E2E", "result": "pass", "seconds": 1.5}
FAIL = {"name": "2 ACK loss", "result": "fail", "seconds": 2.0}


class Build(unittest.TestCase):
    def result(self, status, cases, complete=True):
        return ns.build("j", "Q1", status, cases, complete, {}, ENV)["result"]

    def test_pass_only_when_status_success_and_all_cases_pass(self):
        self.assertEqual(self.result("success", [PASS]), "pass")
        self.assertEqual(self.result("success", []), "pass")  # a job with no cases file
        self.assertEqual(self.result("success", [PASS, FAIL]), "fail")
        self.assertEqual(self.result("failure", [PASS]), "fail")
        self.assertEqual(self.result("cancelled", [PASS]), "fail")
        self.assertEqual(self.result("success", [], complete=False), "fail")

    def test_fields(self):
        s = ns.build("j", "Q1", "success", [PASS], True, {"emulatorApi": 34}, ENV)
        self.assertEqual((s["schema"], s["job"], s["gate"], s["commit"]), (1, "j", "Q1", "abc"))
        self.assertEqual(s["run"]["url"], "https://github.com/o/r/actions/runs/7")
        self.assertEqual(s["numbers"], {"emulatorApi": 34})


class Parse(unittest.TestCase):
    def test_number(self):
        self.assertEqual(ns.parse_number("a=3"), ("a", 3))
        self.assertEqual(ns.parse_number("a=3.5"), ("a", 3.5))
        for bad in ("a", "=3", "a=x"):
            with self.assertRaises(ValueError):
                ns.parse_number(bad)

    def test_missing_cases_file_is_incomplete(self):
        self.assertEqual(ns.load_cases(None), ([], True))
        self.assertEqual(ns.load_cases("/nonexistent/cases.json"), ([], False))

    def test_malformed_cases_file_is_incomplete(self):
        with tempfile.TemporaryDirectory() as d:
            for text in ("not json", '{"a": 1}', '[{"name": "x"}]'):
                f = Path(d, "c.json")
                f.write_text(text)
                self.assertEqual(ns.load_cases(str(f)), ([], False), text)

    def test_main_writes_the_file(self):
        with tempfile.TemporaryDirectory() as d:
            cases = Path(d, "cases.json")
            cases.write_text(json.dumps([PASS]))
            ns.main(["--job", "j", "--gate", "Q1", "--status", "success", "--cases", str(cases),
                     "--number", "n=1", "--out", d], ENV)
            self.assertEqual(json.loads(Path(d, "j.json").read_text())["result"], "pass")


if __name__ == "__main__":
    unittest.main()
