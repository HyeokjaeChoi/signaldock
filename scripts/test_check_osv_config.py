"""Tests for check_osv_config.py. Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import datetime
import unittest
from pathlib import Path

import check_osv_config as oc

ROOT = Path(__file__).resolve().parent.parent
T = datetime.date(2026, 11, 16)
GOOD = '[[IgnoredVulns]]\nid = "GHSA-aaaa"\nignoreUntil = 2026-12-16\nreason = "build tool, not shipped"\n'
SCAN = "LOCKFILES=(\n  dashboard/package-lock.json\n  sdk/gradle/verification-metadata.xml\n)\n"


class Config(unittest.TestCase):
    def test_good_sample_passes(self):
        self.assertEqual(oc.config_errors(GOOD, T), [])

    def test_bad_samples_fail(self):
        for bad in (GOOD.replace("2026-12-16", "2026-12-17"), GOOD.replace("reason = ", "why = "),
                    GOOD.replace('"build tool, not shipped"', '"later"'), GOOD.replace("ignoreUntil = 2026-12-16\n", ""),
                    GOOD + '[[PackageOverrides]]\nname = "x"\nignore = true\n'):
            self.assertTrue(oc.config_errors(bad, T), bad)


class Repo(unittest.TestCase):
    def errors(self, configs, tracked, scan=SCAN):
        return oc.repo_errors(scan, configs, tracked, T)

    def test_real_osv_scan_lists_seven_lockfiles(self):
        self.assertEqual(len(oc.lockfiles((ROOT / "scripts/osv-scan.sh").read_text(encoding="utf-8"))), 7)

    def test_config_next_to_a_listed_lockfile_passes(self):
        self.assertEqual(self.errors({"dashboard/osv-scanner.toml": GOOD}, ["dashboard/package-lock.json"]), [])

    def test_root_config_needs_a_root_lockfile(self):
        errors = self.errors({"osv-scanner.toml": GOOD}, ["dashboard/package-lock.json"])
        self.assertTrue(any("no effect" in e for e in errors), errors)

    def test_config_in_a_folder_without_a_lockfile_fails(self):
        errors = self.errors({"server/osv-scanner.toml": GOOD}, ["dashboard/package-lock.json"])
        self.assertTrue(any("no effect" in e for e in errors), errors)

    def test_tracked_lockfile_missing_from_lockfiles_fails(self):
        errors = self.errors({}, ["dashboard/package-lock.json", "perf/report/package-lock.json"])
        self.assertTrue(any("perf/report/package-lock.json" in e and "not listed" in e for e in errors), errors)

    def test_a_bad_config_is_reported_with_its_path(self):
        errors = self.errors({"dashboard/osv-scanner.toml": GOOD.replace("2026-12-16", "2026-12-17")}, [])
        self.assertTrue(any(e.startswith("dashboard/osv-scanner.toml ") for e in errors), errors)

    def test_no_lockfiles_array_fails(self):
        self.assertTrue(self.errors({}, [], scan="echo hi\n"))


if __name__ == "__main__":
    unittest.main()
