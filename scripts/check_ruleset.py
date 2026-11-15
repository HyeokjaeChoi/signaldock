#!/usr/bin/env python3
"""Gate R3 (ADR-0049 decision 9): the ruleset for `main` as a file. Python 3 standard library only.

  check_ruleset.py static            .github/rulesets/main.json (git index) against the workflows (git index)
  check_ruleset.py drift LIVE.json   the live ruleset (GET /repos/{owner}/{repo}/rulesets/{id}) against the file

static (pre-commit, CI `check`): a required context that no job reports blocks every merge, and a PR job that is not
required is not a gate. So the contexts must be exactly the jobs of repo-rules.yml, the job of pr-description.yml, and
the CodeQL matrix languages (`analyze (<language>)`); the rules must be the ones ADR-0049 names.

drift (nightly): the file is a subset of the live ruleset. GitHub adds fields (ids, timestamps, default parameters),
so a file value must appear in the live one, but a live rule type, required context, or merge method that the file
lacks is drift. `bypass_actors` is compared only when the live JSON has it (GitHub shows it to admins only).
"""
import json
import re
import subprocess
import sys

RULESET = ".github/rulesets/main.json"
RULE_TYPES = {"deletion", "non_fast_forward", "pull_request", "required_status_checks"}
GITHUB_ACTIONS_APP_ID = 15368


def job_contexts(text, path):
    """Job ids of a workflow, or their literal `name:`. Errors go to the second list."""
    jobs, errors, cur = [], [], None
    in_jobs = False
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        if re.match(r"^jobs:\s*$", line):
            in_jobs = True
        elif in_jobs and re.match(r"^\S", line):
            in_jobs = False
        if not in_jobs:
            continue
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            cur = m.group(1)
            jobs.append(cur)
            continue
        m = re.match(r"^    name:\s*(.+?)\s*$", line)
        if cur and m:
            name = re.sub(r"\s+#.*$", "", m.group(1)).strip("\"'")
            if "${{" in name:
                errors.append(f"{path}: job {cur} has an expression in name; the context cannot be checked here")
            else:
                jobs[-1] = name
    return jobs, errors


def codeql_languages(text):
    langs = re.findall(r"^\s+language:\s*([A-Za-z0-9_-]+)\s*$", text, re.M)
    named = re.search(r"^    name:\s*analyze \(\$\{\{ matrix\.language \}\}\)\s*$", text, re.M)
    return langs, bool(named)


def static_errors(ruleset, workflows):
    errors = []
    if ruleset.get("name") != "main" or ruleset.get("target") != "branch" or ruleset.get("enforcement") != "active":
        errors.append('the ruleset must be name "main", target "branch", enforcement "active"')
    if ruleset.get("conditions", {}).get("ref_name", {}).get("include") != ["refs/heads/main"]:
        errors.append('conditions.ref_name.include must be ["refs/heads/main"]')
    rules = {r.get("type"): r.get("parameters", {}) for r in ruleset.get("rules", [])}
    if set(rules) != RULE_TYPES or len(rules) != len(ruleset.get("rules", [])):
        errors.append(f"rules must be exactly {sorted(RULE_TYPES)} (once each)")
        return errors
    if rules["pull_request"].get("allowed_merge_methods") != ["squash"]:
        errors.append('pull_request.allowed_merge_methods must be ["squash"]')
    rsc = rules["required_status_checks"]
    if rsc.get("strict_required_status_checks_policy") is not True:
        errors.append("strict_required_status_checks_policy must be true")
    contexts = [c.get("context") for c in rsc.get("required_status_checks", [])]
    if len(set(contexts)) != len(contexts):
        errors.append("a required context is listed twice")
    for c in rsc.get("required_status_checks", []):
        if c.get("integration_id") != GITHUB_ACTIONS_APP_ID:
            errors.append(f"{c.get('context')}: integration_id must be {GITHUB_ACTIONS_APP_ID} (GitHub Actions)")

    expected = []
    for path in (".github/workflows/repo-rules.yml", ".github/workflows/pr-description.yml"):
        text = workflows.get(path)
        if text is None:
            errors.append(f"{path} is missing")
            continue
        if re.search(r"^\s+paths(-ignore)?:", text, re.M):
            errors.append(f"{path} has a paths filter; a required job must run on every PR")
        jobs, errs = job_contexts(text, path)
        errors += errs
        expected += jobs
    text = workflows.get(".github/workflows/codeql.yml")
    if text is None:
        errors.append(".github/workflows/codeql.yml is missing")
    else:
        langs, named = codeql_languages(text)
        if not named or not langs:
            errors.append("codeql.yml: the job must be named `analyze (${{ matrix.language }})` with matrix languages")
        expected += [f"analyze ({lang})" for lang in langs]
    for c in sorted(set(expected) - set(contexts)):
        errors.append(f"{c}: a PR job with no required context in {RULESET}")
    for c in sorted(set(contexts) - set(expected)):
        errors.append(f"{c}: a required context that no workflow job reports (it would block every merge)")
    return errors


def subset(want, got, path=""):
    """Differences where `want` (the file) is not contained in `got` (the live JSON). Lists of dicts match as sets."""
    if isinstance(want, dict):
        if not isinstance(got, dict):
            return [f"{path or '.'}: expected an object"]
        return [d for k, v in want.items() for d in (subset(v, got[k], f"{path}.{k}") if k in got else [f"{path}.{k}: missing in the live ruleset"])]
    if isinstance(want, list):
        if not isinstance(got, list):
            return [f"{path}: expected a list"]
        out = []
        for w in want:
            if not any(not subset(w, g, path) for g in got):
                out.append(f"{path}: {json.dumps(w, sort_keys=True)} is not in the live ruleset")
        if all(isinstance(w, (str, int, bool)) for w in want) and len(got) != len(want):
            out.append(f"{path}: the live ruleset has {len(got)} entries, the file {len(want)}")
        return out
    return [] if want == got else [f"{path}: file has {want!r}, live has {got!r}"]


def drift_errors(file_rs, live_rs):
    errors = []
    for key in ("name", "target", "enforcement", "conditions"):
        errors += subset(file_rs.get(key), live_rs.get(key), key)
    if "bypass_actors" in live_rs and live_rs["bypass_actors"] is not None:
        errors += [f"bypass_actors: {d}" for d in subset(file_rs.get("bypass_actors", []), live_rs["bypass_actors"])]
        if len(live_rs["bypass_actors"]) != len(file_rs.get("bypass_actors", [])):
            errors.append("bypass_actors: the live ruleset lists a different number of actors")
    live_rules = {r["type"]: r for r in live_rs.get("rules", [])}
    file_rules = {r["type"]: r for r in file_rs.get("rules", [])}
    for t in sorted(set(live_rules) - set(file_rules)):
        errors.append(f"rules: the live ruleset has an extra rule {t}")
    for t, r in file_rules.items():
        if t not in live_rules:
            errors.append(f"rules: {t} is not in the live ruleset")
            continue
        errors += subset(r.get("parameters", {}), live_rules[t].get("parameters", {}), f"rules.{t}")
        if t == "required_status_checks":
            want = {c["context"] for c in r["parameters"]["required_status_checks"]}
            have = {c["context"] for c in live_rules[t].get("parameters", {}).get("required_status_checks", [])}
            errors += [f"required context {c} is live but not in the file" for c in sorted(have - want)]
    return errors


def staged(path):
    out = subprocess.run(["git", "show", ":" + path], capture_output=True, text=True)
    return out.stdout if out.returncode == 0 else None


def main(argv):
    if argv[:1] == ["static"]:
        text = staged(RULESET)
        if text is None:
            print(f"error: {RULESET} is missing; ADR-0049 decision 9 needs the ruleset file", file=sys.stderr)
            return 1
        workflows = {p: staged(p) for p in (".github/workflows/repo-rules.yml", ".github/workflows/pr-description.yml", ".github/workflows/codeql.yml")}
        errors = static_errors(json.loads(text), workflows)
    elif argv[:1] == ["drift"] and len(argv) == 2:
        with open(RULESET, encoding="utf-8") as f, open(argv[1], encoding="utf-8") as g:
            errors = drift_errors(json.load(f), json.load(g))
    else:
        print(__doc__, file=sys.stderr)
        return 2
    for e in errors:
        print(f"error: {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
