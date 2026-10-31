"""Request templates for the live demo VM measurement (perf/demo-vm/README.md).

Run: python3 perf/demo-vm/gen_templates.py <out.jsonl> [count]
Same bodies as the local load runs (run_one.gen_bodies: 20 events of about 1 KiB each, fixed seed). The salt is the
placeholder ffffffff; perf/demo-vm/collect.js puts a run salt and a unique sequence into every eventId.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import run_one  # noqa: E402

out = sys.argv[1]
count = int(sys.argv[2]) if len(sys.argv) > 2 else 200
run_one.gen_bodies("ffffffff", count, out)
print(f"wrote {count} templates to {out}")
