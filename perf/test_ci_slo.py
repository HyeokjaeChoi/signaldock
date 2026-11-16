import unittest

import ci_slo


def summary(p95=40.0, reqs=2400, bad=None, dropped=None):
    m = {"http_req_duration{phase:measure}": {"p(95)": p95}, "http_reqs{phase:measure}": {"count": reqs}}
    if bad is not None:
        m["non200"] = {"count": bad}
    if dropped is not None:
        m["dropped_iterations"] = {"count": dropped}
    return {"metrics": m}


BASE = {"runnerClass": "ubuntu-latest", "p95Ms": 20}


class Verdict(unittest.TestCase):
    def check(self, s, base=BASE, cls="ubuntu-latest"):
        return ci_slo.verdict(s, base, cls, 2400)["result"]

    def test_pass_at_the_limit(self):
        self.assertEqual(self.check(summary(p95=40.0)), "pass")

    def test_p95_above_twice_the_baseline_fails(self):
        self.assertEqual(self.check(summary(p95=40.1)), "fail")

    def test_one_bad_request_fails(self):
        self.assertEqual(self.check(summary(bad=1)), "fail")

    def test_dropped_iteration_fails(self):
        self.assertEqual(self.check(summary(dropped=1)), "fail")

    def test_too_few_requests_fails(self):
        self.assertEqual(self.check(summary(reqs=2000)), "fail")

    def test_other_runner_class_fails(self):
        self.assertEqual(self.check(summary(), cls="ubuntu-24.04-arm"), "fail")

    def test_measure_mode_has_no_p95_rule(self):
        self.assertEqual(self.check(summary(p95=9999), base=None), "pass")
        self.assertEqual(self.check(summary(bad=3), base=None), "fail")


if __name__ == "__main__":
    unittest.main()
