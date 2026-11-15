import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location("pv", os.path.join(os.path.dirname(__file__), "powerloss-verify.py"))
pv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pv)


def line(eid, status, name):
    return f"{eid}\t{status}\t{name}\n"


BASE = [line("a", "accepted", "pl_pre_0"), line("b", "accepted", "pl_pending_0"), line("c", "accepted", "pl_post_0")]


class PowerLossVerify(unittest.TestCase):
    def test_clean_run_with_a_lost_tail_and_a_resend(self):
        lines = BASE + [line("c", "duplicate", "pl_post_0")]  # tail pl_tail_0 never arrived
        problems, numbers = pv.verify(lines, 1, 1, 1, 1)
        self.assertEqual([], problems)
        self.assertEqual({"resends": 1, "lostAfterCheckpoint": 1}, numbers)

    def test_conflict_fails(self):
        problems, _ = pv.verify(BASE + [line("a", "conflict", "pl_pre_0")], 1, 1, 1, 0)
        self.assertTrue(any("conflict" in p for p in problems))

    def test_a_second_accepted_fails(self):
        problems, _ = pv.verify(BASE + [line("a", "accepted", "pl_pre_0")], 1, 1, 1, 0)
        self.assertTrue(any("expected accepted once" in p for p in problems))

    def test_a_pending_event_that_never_arrived_fails(self):
        problems, _ = pv.verify([BASE[0], BASE[2]], 1, 1, 1, 0)
        self.assertTrue(any("pl_pending" in p for p in problems))


if __name__ == "__main__":
    unittest.main()
