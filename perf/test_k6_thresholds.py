"""Guards the k6 thresholds of ticket 04 (ADR-0049 decision 7): how the run scripts read a k6 exit, that load.js and overload.js keep their
thresholds, and that perf/demo-vm/collect.js has none.
The proof that each threshold fails on a fault is perf/threshold-proof/proof.py (needs k6, so it is not run here).
Run: python3 -m unittest discover -s perf -p 'test_*.py'"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import common as c  # noqa: E402

# Shape of `k6 --summary-export` (k6 2.3.0): a threshold value is true when the threshold was CROSSED.
SUMMARY = {"metrics": {
    "http_req_failed{phase:measure}": {"fails": 90, "passes": 10, "value": 0.1, "thresholds": {"rate<0.01": True}},
    "unexpected_status": {"fails": 100, "passes": 0, "value": 0, "thresholds": {"rate==0": False}},
    "dropped_iterations": {"count": 0, "rate": 0, "thresholds": {"count==0": False}},
    "iterations": {"count": 100, "rate": 5},
}}
PERF = pathlib.Path(__file__).parent


class K6ExitTest(unittest.TestCase):
    def test_only_a_true_threshold_is_crossed(self):
        self.assertEqual(["http_req_failed{phase:measure}: rate<0.01"], c.crossed_thresholds(SUMMARY))

    def test_exit_0_has_no_reason(self):
        self.assertEqual(([], []), c.k6_exit_reasons(0, SUMMARY))

    def test_exit_99_is_a_fail_that_names_the_threshold_not_an_invalid_run(self):
        invalid, fail = c.k6_exit_reasons(99, SUMMARY)
        self.assertEqual([], invalid)
        self.assertEqual(1, len(fail))
        self.assertIn("rate<0.01", fail[0])

    def test_exit_99_with_dropped_iterations_keeps_the_run_invalid(self):
        only = {"metrics": {"dropped_iterations": {"count": 12, "thresholds": {"count==0": True}}}}
        invalid, fail = c.k6_exit_reasons(99, only)
        self.assertEqual(1, len(invalid))
        self.assertIn("dropped_iterations", invalid[0])
        self.assertEqual([], fail)  # not a server failure

    def test_exit_99_with_dropped_iterations_and_a_server_threshold_is_both(self):
        both = {"metrics": {**SUMMARY["metrics"], "dropped_iterations": {"count": 12, "thresholds": {"count==0": True}}}}
        invalid, fail = c.k6_exit_reasons(99, both)
        self.assertEqual(1, len(invalid))
        self.assertEqual(1, len(fail))
        self.assertIn("rate<0.01", fail[0])
        self.assertNotIn("dropped_iterations", fail[0])

    def test_exit_99_without_a_crossed_threshold_is_invalid_not_a_server_fail(self):
        invalid, fail = c.k6_exit_reasons(99, {"metrics": {"iterations": {"count": 1}}})
        self.assertEqual(1, len(invalid))
        self.assertEqual([], fail)

    def test_only_true_counts_as_crossed(self):
        odd = {"metrics": {"m": {"thresholds": {"rate<1": {"ok": True}, "count==0": False}}}}
        self.assertEqual([], c.crossed_thresholds(odd))

    def test_any_other_exit_is_invalid(self):
        for code in (1, 107, 105, None):
            invalid, fail = c.k6_exit_reasons(code, SUMMARY)
            self.assertEqual(1, len(invalid), code)
            self.assertEqual([], fail, code)


class ScriptsDeclareThresholdsTest(unittest.TestCase):
    def thresholds(self, rel):
        text = (PERF / rel).read_text()
        self.assertIn("thresholds: {", text, rel)
        return text.split("thresholds: {", 1)[1]

    def test_load_has_two_rules_http_req_failed_on_the_measure_phase_and_dropped_iterations(self):
        t = self.thresholds("load.js")
        self.assertIn("'http_req_failed{phase:measure}': ['rate<0.01']", t)
        self.assertIn("dropped_iterations: ['count==0']", t)
        self.assertNotIn("p(95)", t)  # latency rules are relative to a baseline of the same run (ADR-0013): they stay in Python

    def test_collect_has_no_thresholds_its_verdict_stays_in_python(self):
        self.assertNotIn("thresholds:", (PERF / "demo-vm" / "collect.js").read_text())

    def test_overload_states_what_overload_must_show(self):
        t = self.thresholds("overload.js")
        for rule in ("unexpected_status: ['rate==0']", "dropped_iterations: ['count==0']"):
            self.assertIn(rule, t)
        self.assertNotIn("p(95)", t)
        self.assertNotIn("http_req_failed", t)  # the run must shed with 503, which k6 counts as failed


if __name__ == "__main__":
    unittest.main()
