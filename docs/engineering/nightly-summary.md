# Nightly run summary (gate Q2)

Every job of `.github/workflows/nightly.yml` writes one JSON file with `scripts/nightly-summary.py` and uploads the folder as the artifact `nightly-summary-<job>` (ADR-0049). `scripts/check-repo-rules.sh` fails when a job of that workflow has no `nightly-summary.py` step. The step runs with `if: always()`, so a failed job still leaves a file.

Why: numbers must come from a merged CI run or a committed file (ADR-0049 decision 10). The artifact is the source. A later PR may copy one snapshot into `perf/results/` or `docs/engineering/verification/`.

## Step in a job

```yaml
- name: Summary (gate Q2)
  if: always()
  env:
    JOB_STATUS: ${{ job.status }}
  run: python3 -I scripts/nightly-summary.py --job <job> --gate <gate> --status "$JOB_STATUS" --cases <cases.json> --number key=value
- uses: actions/upload-artifact@<sha>
  if: always()
  with:
    name: nightly-summary-<job>
    path: nightly-summary/
    if-no-files-found: error
```

Flags: `--job` (the job id, also the file name), `--gate` (spec gate id, for example `Q1`), `--status` (`job.status`), `--cases` (optional file with the case list), `--number key=value` (repeat; integer or float), `--out` (default `nightly-summary`).

## File format (`schema` 1)

```json
{
  "schema": 1,
  "job": "e2e-delivery",
  "gate": "Q1",
  "commit": "<GITHUB_SHA>",
  "run": {"id": "...", "attempt": "1", "event": "schedule", "url": "https://github.com/<repo>/actions/runs/<id>"},
  "result": "pass",
  "cases": [{"name": "1 normal E2E", "result": "pass", "seconds": 33.7}],
  "numbers": {"emulatorApi": 34}
}
```

- `result` is `pass` only when `--status` is `success`, every case has `result` `pass`, and the `--cases` file exists. Otherwise `fail` (a cancelled job is `fail`).
- `cases` comes from the job's own script: a JSON list of `{name, result: "pass"|"fail", seconds}`. A job without cases omits `--cases` and has an empty list. `scripts/e2e/e2e_cases.py` writes this list when `E2E_RESULT_JSON` names a file.
- `numbers` holds the key numbers of the gate (counts, milliseconds, bytes). Name keys in camelCase with the unit in the name when it is not obvious (`p95Ms`, `lostEvents`). A number is a measured value of that run, never a threshold.
- Job times are not in the file. Read them from the run (`gh run view <id> --json jobs`).

## Reading a result

```bash
gh run download <run id> -n nightly-summary-e2e-delivery
```
