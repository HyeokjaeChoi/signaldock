#!/usr/bin/env python3
"""The measurement matrix of performance-plan.md: 24 combinations x 3 pools x 3 repetitions = 216 runs.

  python3 perf/matrix.py                 # list the runs in order (default; runs nothing)
  python3 perf/matrix.py --execute       # run them serially; finished run directories are skipped (resume)

Pool order rotates per repetition: 2-4-8, 4-8-2, 8-2-4. Repetitions are the outer loop, so an interruption leaves
whole repetitions comparable. Seeds must exist first (perf/seed.py build, same --day for every seed).
Do not run Android builds or emulators while this executes.
"""
import argparse
import itertools
import subprocess
import sys

import common as c

ROTATION = [(2, 4, 8), (4, 8, 2), (8, 2, 4)]


def runs(reps):
    combos = list(itertools.product([10000, 100000], ["uniform", "recent70"], [1, 7, 30], [1, 5]))
    for rep in range(1, reps + 1):
        order = ROTATION[(rep - 1) % 3]
        for size, dist, window, clients in combos:
            for pool in order:
                yield {"rep": rep, "size": size, "dist": dist, "window": window, "clients": clients, "pool": pool,
                       "position": "-".join(map(str, order)),
                       "id": f"m-r{rep}-{size}-{dist}-w{window}-c{clients}-p{pool}"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--execute", action="store_true")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None, help="stop after this many runs")
    a = ap.parse_args()
    done = 0
    for r in runs(a.reps):
        out = c.RESULTS / r["id"]
        if not a.execute:
            print(r["id"], "(done)" if (out / "summary.json").exists() else "")
            continue
        if (out / "summary.json").exists():
            continue
        if a.limit is not None and done >= a.limit:
            break
        done += 1
        cmd = [sys.executable, str(c.PERF / "run_one.py"), "--seed-size", str(r["size"]), "--dist", r["dist"], "--window", str(r["window"]),
               "--clients", str(r["clients"]), "--pool", str(r["pool"]), "--rep", str(r["rep"]), "--position", r["position"], "--run-id", r["id"]]
        if subprocess.run(cmd).returncode:
            print(f"{r['id']} failed; see {out}/ERROR.txt. Fix the cause, move the directory aside, and rerun.", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
