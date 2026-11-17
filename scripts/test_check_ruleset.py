"""Tests for check_ruleset.py. Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import copy
import json
import unittest
from pathlib import Path

import check_ruleset as cr

ROOT = Path(__file__).resolve().parent.parent
RULESET = json.loads((ROOT / cr.RULESET).read_text(encoding="utf-8"))
WORKFLOWS = {p: (ROOT / p).read_text(encoding="utf-8") for p in (
    ".github/workflows/repo-rules.yml", ".github/workflows/pr-description.yml", ".github/workflows/codeql.yml")}


def rule(rs, kind):
    return next(r for r in rs["rules"] if r["type"] == kind)


def live_of(rs):
    """What GitHub returns: the file plus ids, timestamps, and parameters the file leaves out."""
    live = copy.deepcopy(rs)
    live.update(id=1, source_type="Repository", current_user_can_bypass="never")
    rule(live, "pull_request")["parameters"]["automatic_copilot_code_review_enabled"] = False
    return live


class Static(unittest.TestCase):
    def errors(self, rs=None, workflows=None):
        return cr.static_errors(rs or RULESET, workflows or WORKFLOWS)

    def test_committed_ruleset_matches_the_workflows(self):
        self.assertEqual(self.errors(), [])

    def test_a_pr_job_without_a_required_context_fails(self):
        rs = copy.deepcopy(RULESET)
        checks = rule(rs, "required_status_checks")["parameters"]["required_status_checks"]
        checks[:] = [c for c in checks if c["context"] != "sdk-test"]
        self.assertTrue(any("sdk-test" in e for e in self.errors(rs)))

    def test_a_required_context_no_job_reports_fails(self):
        rs = copy.deepcopy(RULESET)
        rule(rs, "required_status_checks")["parameters"]["required_status_checks"].append(
            {"context": "analyse (swift)", "integration_id": cr.GITHUB_ACTIONS_APP_ID})
        self.assertTrue(any("analyse (swift)" in e for e in self.errors(rs)))

    def test_a_new_workflow_job_must_be_required(self):
        wf = dict(WORKFLOWS)
        wf[".github/workflows/repo-rules.yml"] += "\n  new-gate:\n    runs-on: ubuntu-latest\n"
        self.assertTrue(any("new-gate" in e for e in self.errors(workflows=wf)))

    def test_a_new_codeql_language_must_be_required(self):
        wf = dict(WORKFLOWS)
        wf[".github/workflows/codeql.yml"] += "          - os: ubuntu-latest\n            language: ruby\n"
        self.assertTrue(any("analyze (ruby)" in e for e in self.errors(workflows=wf)))

    def test_a_paths_filter_fails(self):
        wf = dict(WORKFLOWS)
        wf[".github/workflows/repo-rules.yml"] = wf[".github/workflows/repo-rules.yml"].replace(
            "  pull_request:\n", "  pull_request:\n    paths: ['sdk/**']\n", 1)
        self.assertTrue(any("paths filter" in e for e in self.errors(workflows=wf)))

    def test_a_paths_filter_in_codeql_fails(self):
        wf = dict(WORKFLOWS)
        wf[".github/workflows/codeql.yml"] = wf[".github/workflows/codeql.yml"].replace(
            "  pull_request:\n", "  pull_request:\n    paths: ['sdk/**']\n", 1)
        self.assertTrue(any("paths filter" in e for e in self.errors(workflows=wf)))

    def test_rule_set_merge_method_and_strict_policy(self):
        for mutate, text in (
            (lambda r: rule(r, "pull_request")["parameters"].update(allowed_merge_methods=["squash", "merge"]), "squash"),
            (lambda r: rule(r, "required_status_checks")["parameters"].update(strict_required_status_checks_policy=False), "strict"),
            (lambda r: r["rules"].remove(rule(r, "non_fast_forward")), "exactly"),
            (lambda r: r["rules"].remove(rule(r, "deletion")), "exactly"),
            (lambda r: r.update(enforcement="evaluate"), "active"),
        ):
            rs = copy.deepcopy(RULESET)
            mutate(rs)
            self.assertTrue(any(text in e for e in self.errors(rs)), text)


class Drift(unittest.TestCase):
    def test_live_ruleset_with_extra_defaults_has_no_drift(self):
        self.assertEqual(cr.drift_errors(RULESET, live_of(RULESET)), [])

    def test_changes_to_the_live_ruleset_are_drift(self):
        for mutate, text in (
            (lambda l: rule(l, "pull_request")["parameters"].update(allowed_merge_methods=["squash", "rebase"]), "allowed_merge_methods"),
            (lambda l: rule(l, "required_status_checks")["parameters"].update(strict_required_status_checks_policy=False), "strict"),
            (lambda l: rule(l, "required_status_checks")["parameters"]["required_status_checks"].pop(), "is not in the live"),
            (lambda l: rule(l, "required_status_checks")["parameters"]["required_status_checks"].append({"context": "extra"}), "live but not in the file"),
            (lambda l: rule(l, "required_status_checks")["parameters"]["required_status_checks"].append(
                {"context": rule(RULESET, "required_status_checks")["parameters"]["required_status_checks"][0]["context"], "integration_id": 999}),
             "live but not in the file"),
            (lambda l: l["rules"].remove(rule(l, "non_fast_forward")), "non_fast_forward is not in the live"),
            (lambda l: l["rules"].append({"type": "creation"}), "extra rule creation"),
            (lambda l: l.update(enforcement="disabled"), "enforcement"),
            (lambda l: l["conditions"]["ref_name"].update(include=["~ALL"]), "conditions"),
            (lambda l: l.update(bypass_actors=[{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]), "bypass_actors"),
        ):
            live = live_of(RULESET)
            mutate(live)
            errors = cr.drift_errors(RULESET, live)
            self.assertTrue(any(text in e for e in errors), (text, errors))

    def test_bypass_actors_are_skipped_when_the_live_json_hides_them(self):
        live = live_of(RULESET)
        live.pop("bypass_actors", None)
        self.assertEqual(cr.drift_errors(RULESET, live), [])


if __name__ == "__main__":
    unittest.main()
