#!/usr/bin/env python3
"""iOS SDK size and host cost (ADR-0047 amendment "ticket 16, measurement method"; the iOS form of ADR-0020).

Called by scripts/measure-ios-sdk.sh, which builds the apps first. Do not run it by hand.
  size <work> <out>
  run  <work> <out> <simulator> <runs> <burst_runs> <burst> <idle_s> <sdk_version> <zip>
"""
import csv
import json
import math
import os
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ARMS = ("sdk", "nosdk")
BUNDLE = {"sdk": "local.signaldock.consumerios", "nosdk": "local.signaldock.consumerios.nosdk"}
WARMUP = 2  # launches per arm that are not counted (first start after install)


def sh(*cmd, env=None, check=True):
    return subprocess.run(cmd, capture_output=True, text=True, check=check, env=env).stdout


def median(v):
    v = sorted(v)
    n = len(v)
    return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2


def pct(v, p):
    """Nearest rank: with n=20, p95 is the 19th value."""
    v = sorted(v)
    return v[max(1, math.ceil(p / 100 * len(v))) - 1]


def tree_bytes(path):
    return sum(f.stat().st_size for f in Path(path).rglob("*") if f.is_file())


def write_csv(path, header, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


# ---------------------------------------------------------------- size
def segments(binary):
    """Mach-O segment sizes from `size -m`: {name: bytes}, and section sizes {segment.section: bytes}."""
    seg, sec, cur = {}, {}, None
    for line in sh("size", "-m", str(binary)).splitlines():
        m = re.match(r"Segment (\S+): (\d+)", line)
        if m:
            cur = m.group(1)
            seg[cur] = int(m.group(2))
            continue
        m = re.match(r"\s+Section (\S+): (\d+)", line)
        if m and cur:
            sec[f"{cur}.{m.group(1)}"] = int(m.group(2))
    return seg, sec


def linkmap(path):
    """Bytes the linker attributes to each object file, and for the package object (SignalDock.o) by segment.section."""
    objects, ranges, per_obj, per_sec = {}, [], {}, {}
    mode = None
    for line in Path(path).read_text(errors="replace").splitlines():
        if line.startswith("# Object files:"):
            mode = "obj"
        elif line.startswith("# Sections:"):
            mode = "sec"
        elif line.startswith("# Symbols:"):
            mode = "sym"
        elif line.startswith("# Dead Stripped"):
            break
        elif line.startswith("#"):
            continue
        elif mode == "obj":
            m = re.match(r"\[\s*(\d+)\]\s+(.*)", line)
            if m:
                objects[int(m.group(1))] = m.group(2)
        elif mode == "sec":
            a, s, seg, name = line.split("\t")
            ranges.append((int(a, 16), int(a, 16) + int(s, 16), f"{seg}.{name}"))
        elif mode == "sym":
            parts = line.split("\t", 2)
            m = len(parts) == 3 and re.match(r"\[\s*(\d+)\]", parts[2])
            if not m:
                continue
            idx, addr, size = int(m.group(1)), int(parts[0], 16), int(parts[1], 16)
            name = objects.get(idx, "?")
            per_obj[name] = per_obj.get(name, 0) + size
            if name.endswith("/SignalDock.o"):
                sec = next((r[2] for r in ranges if r[0] <= addr < r[1]), "?")
                per_sec[sec] = per_sec.get(sec, 0) + size
    short = {}
    for name, size in per_obj.items():
        short[Path(name).name] = short.get(Path(name).name, 0) + size
    return short, per_sec


def size(work, out):
    work, out = Path(work), Path(out)
    rows, info = [], {}
    for arm in ARMS:
        app = work / f"size-{arm}.xcarchive/Products/Applications/ConsumerIOS.app"
        binary = app / "ConsumerIOS"
        seg, sec = segments(binary)
        ipa = out / f".{arm}.ipa"
        with zipfile.ZipFile(ipa, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for f in sorted(app.rglob("*")):
                if f.is_file():
                    z.write(f, "Payload/ConsumerIOS.app/" + f.relative_to(app).as_posix())
        ipa_bytes = ipa.stat().st_size
        ipa.unlink()
        bundle = app / "SignalDock_SignalDock.bundle"
        objs, per_sec = linkmap(next(work.glob(f"linkmap-{arm}-ConsumerIOS.txt")))
        info[arm] = {
            "app_bytes": tree_bytes(app),
            "binary_bytes": binary.stat().st_size,
            "ipa_bytes": ipa_bytes,
            "resource_bundle_bytes": tree_bytes(bundle) if bundle.exists() else 0,
            "text_segment_bytes": seg.get("__TEXT", 0),
            "data_const_segment_bytes": seg.get("__DATA_CONST", 0),
            "data_segment_bytes": seg.get("__DATA", 0),
            "linkedit_segment_bytes": seg.get("__LINKEDIT", 0),
            "text_text_section_bytes": sec.get("__TEXT.__text", 0),
            "package_object_bytes": objs.get("SignalDock.o", 0),
            "app_objects_bytes": sum(v for k, v in objs.items() if k != "SignalDock.o"),
        }
        info[arm]["_objs"], info[arm]["_sec"] = objs, per_sec
        info[arm]["frameworks_folder"] = (app / "Frameworks").exists()
        dyl = sh("otool", "-L", str(binary)).splitlines()[1:]
        info[arm]["_dylibs"] = sorted(l.strip().split(" (")[0] for l in dyl)
        info[arm]["sha256_binary"] = sh("shasum", "-a", "256", str(binary)).split()[0]
    for k in info["sdk"]:
        if k.startswith("_") or k in ("frameworks_folder", "sha256_binary"):
            continue
        rows.append((k, info["nosdk"][k], info["sdk"][k], info["sdk"][k] - info["nosdk"][k]))
    write_csv(out / "size.csv", ("metric", "without_sdk_bytes", "with_sdk_bytes", "delta_bytes"), rows)
    sec_rows = sorted(info["sdk"]["_sec"].items(), key=lambda kv: -kv[1])
    write_csv(out / "sdk-sections.csv", ("segment.section", "bytes_in_SignalDock.o"), sec_rows)
    obj_rows = sorted(
        {*info["sdk"]["_objs"], *info["nosdk"]["_objs"]},
        key=lambda k: -info["sdk"]["_objs"].get(k, 0),
    )
    write_csv(
        out / "link-objects.csv",
        ("object", "without_sdk_bytes", "with_sdk_bytes"),
        [(k, info["nosdk"]["_objs"].get(k, 0), info["sdk"]["_objs"].get(k, 0)) for k in obj_rows],
    )
    with open(out / "dependencies.txt", "w") as f:
        for arm in ARMS:
            f.write(f"== {arm}: dynamic libraries linked by the main binary (otool -L); Frameworks folder in the app: "
                    f"{info[arm]['frameworks_folder']}\n")
            f.write("\n".join(info[arm]["_dylibs"]) + "\n")
        added = sorted(set(info["sdk"]["_dylibs"]) - set(info["nosdk"]["_dylibs"]))
        f.write("== linked with the SDK and not without it\n" + ("\n".join(added) if added else "(none)") + "\n")
    json.dump({a: {k: v for k, v in info[a].items() if not k.startswith("_")} for a in ARMS},
              open(out / ".size.json", "w"), indent=1)


# ---------------------------------------------------------------- run
class Sim:
    def __init__(self, udid):
        self.udid = udid
        self.env = dict(os.environ)
        self.env["SIMCTL_CHILD_SIGNALDOCK_ENDPOINT"] = "http://127.0.0.1:9"  # nothing answers: no event leaves the Mac
        self.env["SIMCTL_CHILD_SIGNALDOCK_COLLECTION_KEY"] = "host-cost-dummy-key"
        self.data = {}

    def install(self, arm, app):
        sh("xcrun", "simctl", "install", self.udid, str(app))
        self.data[arm] = Path(sh("xcrun", "simctl", "get_app_container", self.udid, BUNDLE[arm], "data").strip())

    def launch(self, arm, burst, idle):
        """One cold start. Returns the probe record plus wall time of `simctl launch` and the footprint after the idle time."""
        result = self.data[arm] / "Documents/hostcost.json"
        result.unlink(missing_ok=True)
        env = dict(self.env, SIMCTL_CHILD_HOSTCOST_BURST=str(burst))
        t0 = time.perf_counter()
        line = sh("xcrun", "simctl", "launch", "--terminate-running-process", self.udid, BUNDLE[arm], env=env)
        wall_ms = (time.perf_counter() - t0) * 1000
        pid = int(line.strip().rsplit(" ", 1)[1])
        rec = None
        for _ in range(240):
            try:
                rec = json.loads(result.read_text())
                if rec.get("done") and "first_view_after_process_start_ms" in rec:
                    break
            except (FileNotFoundError, ValueError):
                pass
            time.sleep(0.25)
        else:
            sys.exit(f"error: the {arm} app did not finish (burst {burst}); record: {rec}")
        time.sleep(idle)
        fp = self.footprint(pid)
        sh("xcrun", "simctl", "terminate", self.udid, BUNDLE[arm], check=False)
        rec["launch_wall_ms"] = wall_ms
        rec["footprint_bytes"] = fp
        return rec

    @staticmethod
    def footprint(pid):
        tmp = f"/tmp/.ioscost-fp-{os.getpid()}.json"
        sh("footprint", "-j", tmp, str(pid))
        try:
            return json.load(open(tmp))["processes"][0]["footprint"]
        finally:
            os.unlink(tmp)


def stats(v):
    return {"n": len(v), "p50": median(v), "p95": pct(v, 95), "max": max(v), "min": min(v)}


def ms(ns):
    return ns / 1e6


def run(work, out, udid, runs, burst_runs, burst, idle, version, zip_path):
    work, out = Path(work), Path(out)
    runs, burst_runs, burst, idle = int(runs), int(burst_runs), int(burst), int(idle)
    sim = Sim(udid)
    load_start = os.getloadavg()
    for arm in ARMS:
        sim.install(arm, work / f"probe-{arm}/dd/Build/Products/Release-iphonesimulator/ConsumerIOS.app")
    print("=== 3. startup and idle memory (%d runs per arm, interleaved) ===" % runs)
    for arm in ARMS:
        for _ in range(WARMUP):
            sim.launch(arm, 0, 1)
    startup = []
    for i in range(1, runs + 1):
        for arm in (ARMS if i % 2 else ARMS[::-1]):
            r = sim.launch(arm, 0, idle)
            if arm == "sdk" and r.get("init_result") != "success":
                sys.exit(f"error: initialize() did not succeed in run {i}: {r.get('init_result')}")
            startup.append((arm, i, r))
        print(f"  run {i} done")
    print("=== 4. memory and track() cost after %d track() calls (%d runs per arm) ===" % (burst, burst_runs))
    bursts = []
    for i in range(1, burst_runs + 1):
        for arm in (ARMS if i % 2 else ARMS[::-1]):
            r = sim.launch(arm, burst, idle)
            if arm == "sdk" and (r.get("init_result") != "success" or r.get("track_failed", 1) != 0):
                sys.exit(f"error: initialize() or track() failed in burst run {i}: {r}")
            bursts.append((arm, i, r))
        print(f"  burst run {i} done")
    load_end = os.getloadavg()

    write_csv(out / "startup.csv",
              ("arm", "run", "launch_wall_ms", "first_view_after_process_start_ms", "init_started_after_process_start_ms",
               "footprint_idle_bytes"),
              [(a, i, f"{r['launch_wall_ms']:.1f}", f"{r['first_view_after_process_start_ms']:.1f}",
                f"{r['init_started_after_process_start_ms']:.1f}", r["footprint_bytes"]) for a, i, r in startup])
    write_csv(out / "init.csv", ("arm", "run", "init_main_ns", "init_wall_ns"),
              [(a, i, r["init_main_ns"], r["init_wall_ns"]) for a, i, r in startup])
    write_csv(out / "memory-burst.csv", ("arm", "run", "footprint_after_burst_bytes"),
              [(a, i, r["footprint_bytes"]) for a, i, r in bursts])
    samples = [(i, j, m, w) for a, i, r in bursts if a == "sdk" for j, (m, w) in enumerate(r["track_main_wall_ns"])]
    write_csv(out / "track-samples.csv", ("burst_run", "call", "main_ns", "wall_ns"), samples)

    sz = json.load(open(out / ".size.json"))
    (out / ".size.json").unlink()
    S = lambda arm, f: [f(r) for a, _, r in startup if a == arm]
    B = lambda arm: [r["footprint_bytes"] for a, _, r in bursts if a == arm]
    paired = lambda arm1, arm2, vals: [x - y for x, y in zip(vals(arm1), vals(arm2))]
    first_view = lambda arm: S(arm, lambda r: r["first_view_after_process_start_ms"])
    wall = lambda arm: S(arm, lambda r: r["launch_wall_ms"])
    fp = lambda arm: S(arm, lambda r: r["footprint_bytes"])
    init_main = [ms(r["init_main_ns"]) for a, _, r in startup if a == "sdk"]
    init_wall = [ms(r["init_wall_ns"]) for a, _, r in startup if a == "sdk"]
    floor_main = [ms(r["init_main_ns"]) for a, _, r in startup if a == "nosdk"]
    floor_wall = [ms(r["init_wall_ns"]) for a, _, r in startup if a == "nosdk"]
    tm = [ms(m) for _, _, m, _ in samples]
    tw = [ms(w) for _, _, _, w in samples]
    first_calls = [ms(r["track_main_wall_ns"][0][0]) for a, _, r in bursts if a == "sdk"]
    mb = lambda v: v / 1048576
    fmt = lambda s, d=2: f"{s['p50']:.{d}f} / {s['p95']:.{d}f} / {s['max']:.{d}f}"
    pd_fv, pd_wall, pd_fp, pd_b = (paired("sdk", "nosdk", f) for f in (first_view, wall, fp, B))
    sd = lambda k: sz["sdk"][k]
    nd = lambda k: sz["nosdk"][k]
    dl = lambda k: sd(k) - nd(k)

    xcode = sh("xcodebuild", "-version").replace("\n", " ").strip()
    dev = json.loads(sh("xcrun", "simctl", "list", "devices", "-j"))["devices"]
    sim_dev, sim_rt = next((d, rt) for rt, ds in dev.items() for d in ds if d["udid"] == udid)
    sim_runtime = re.sub(r".*SimRuntime\.(\w+)-(\d+)-(\d+)$", r"\1 \2.\3", sim_rt)
    sim_name = re.sub(r".*SimDeviceType\.", "", sim_dev["deviceTypeIdentifier"]).replace("-", " ")
    git = lambda *a: sh("git", *a).strip()
    meta = {
        "ticket": 16, "adr": "ADR-0047 amendment (ticket 16), ADR-0020",
        "run_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "xcode": xcode, "swift": sh("swift", "--version").splitlines()[0],
        "macos": sh("sw_vers", "-productVersion").strip(), "machine": sh("sysctl", "-n", "machdep.cpu.brand_string").strip(),
        "cpus": sh("sysctl", "-n", "hw.ncpu").strip(), "memory_bytes": int(sh("sysctl", "-n", "hw.memsize")),
        "simulator": {"udid": udid, "device_type": sim_name, "runtime": sim_runtime},
        "loadavg_start": [round(x, 2) for x in load_start], "loadavg_end": [round(x, 2) for x in load_end],
        "sdk_version": version, "release_zip": Path(zip_path).name,
        "release_zip_sha256": sh("shasum", "-a", "256", str(zip_path)).split()[0],
        "git_revision": git("rev-parse", "--short", "HEAD"), "git_dirty": bool(git("status", "--porcelain", "--", ".", ":!perf/sdk-results")),
        "parameters": {"startup_runs_per_arm": runs, "burst_runs_per_arm": burst_runs, "burst_track_calls": burst,
                       "idle_seconds_before_footprint": idle, "warmup_launches_per_arm_not_counted": WARMUP},
        "size_build": {"configuration": "Release", "sdk": "iphoneos", "arch": "arm64", "signing": "CODE_SIGNING_ALLOWED=NO",
                       "command": "xcodebuild archive"},
        "arms": {a: {"binary_sha256": sz[a]["sha256_binary"], **{k: v for k, v in sz[a].items() if k != "sha256_binary"}}
                 for a in ARMS},
    }
    json.dump(meta, open(out / "meta.json", "w"), indent=2)
    (out / "meta.json").write_text((out / "meta.json").read_text() + "\n")

    def row(label, k):
        return f"| {label} | {nd(k):,} | {sd(k):,} | {dl(k):+,} |"

    summary = f"""# iOS SDK cost for a host app (ticket 16)

Measured on a Simulator, not on a physical device: {sim_name} ({sim_runtime}) on {meta['machine']}, macOS {meta['macos']}, {xcode}. One shared host (load average in `meta.json`: start {meta['loadavg_start']}, end {meta['loadavg_end']}). Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0047 amendment "ticket 16, measurement method" and ADR-0020.

Both arms are the independent consumer app (`compatibility/consumer-ios`, Release, Swift 6 mode). The SDK arm uses the SDK from the release ZIP `{meta['release_zip']}` of `{meta['sdk_version']}`. The baseline arm uses an empty package with the same public signatures (`perf/ios-host-cost/baseline`), so the app sources are identical and the baseline binary holds no SDK code. Run {meta['run_utc']}, git {meta['git_revision']}{' (dirty tree)' if meta['git_dirty'] else ''}.

## Size (`xcodebuild archive`, Release, iphoneos arm64, unsigned, stripped by the archive step)

| | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
{row('.app folder, bytes (sum of file sizes)', 'app_bytes')}
{row('Main binary `ConsumerIOS`, bytes', 'binary_bytes')}
{row('`.ipa` form, bytes (unencrypted zip, level 9)', 'ipa_bytes')}
{row('SDK resource bundle `SignalDock_SignalDock.bundle`, bytes', 'resource_bundle_bytes')}
{row('`__TEXT` segment (page aligned), bytes', 'text_segment_bytes')}
{row('`__DATA_CONST` segment, bytes', 'data_const_segment_bytes')}
{row('`__DATA` segment, bytes', 'data_segment_bytes')}
{row('`__LINKEDIT` segment, bytes', 'linkedit_segment_bytes')}
{row('`__TEXT,__text` section (code), bytes', 'text_text_section_bytes')}
{row('Link map: bytes of the package object `SignalDock.o`', 'package_object_bytes')}
{row('Link map: bytes of the app objects (ConsumerApp.o and others)', 'app_objects_bytes')}

The baseline package object is the empty stand-in. The SDK is linked statically into the main binary (`SignalDock.o`); no `Frameworks` folder is added and no dynamic library is added (`dependencies.txt`). Dead code stripping is on, so unused SDK code is not in these numbers. The app objects (mostly `ConsumerApp.o`) also differ, because the app code that calls the real SDK types is larger than the code that calls the empty stand-in; this part belongs to the cost of calling the SDK and is counted in the main binary delta, not in `SignalDock.o`. The `.ipa` here is not encrypted; an App Store build encrypts `__TEXT`, which compresses worse, so a store download is larger than this figure. Per-section and per-object data: `sdk-sections.csv`, `link-objects.csv`.

## Startup ({runs} cold starts per arm, interleaved; {WARMUP} warm-up launches not counted)

Cold start = `simctl terminate`/`launch`, then the app writes the time from the kernel process start record to the first `onAppear` of the root view. `launch wall` is the time of the `xcrun simctl launch` call itself.

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max |
| --- | --- | --- |
| process start to first view | {fmt(stats(first_view('nosdk')), 1)} | {fmt(stats(first_view('sdk')), 1)} |
| `simctl launch` wall time | {fmt(stats(wall('nosdk')), 1)} | {fmt(stats(wall('sdk')), 1)} |

Paired difference (with minus without, run by run), process start to first view: median {median(pd_fv):.1f} ms, min {min(pd_fv):.1f}, max {max(pd_fv):.1f}. `simctl launch` wall time: median {median(pd_wall):.1f} ms, min {min(pd_wall):.1f}, max {max(pd_wall):.1f}.

## `initialize()` and `track()` on the main thread (SDK arm)

Main-thread time follows ADR-0020: from the call until the calling task first suspends (a sentinel task queued on the main actor before the call runs at that moment). It includes call setup, the class loading the call causes, and any other main-actor job that is already queued ahead of the sentinel at that moment (at startup SwiftUI work is), so it is an upper bound for the SDK alone; the floor row shows what the same probe reads around an empty call. A `track()` call later in the run has no queue ahead of it. `wall` runs until the result is ready (mostly off the main thread).

| Call | n | main p50 / p95 / max, ms | wall p50 / p95 / max, ms |
| --- | --- | --- | --- |
| `SignalDockSDK.initialize` (cold start) | {len(init_main)} | {fmt(stats(init_main), 3)} | {fmt(stats(init_wall), 3)} |
| same probe around the empty stand-in `initialize` (floor of the method) | {len(floor_main)} | {fmt(stats(floor_main), 3)} | {fmt(stats(floor_wall), 3)} |
| `track` (`productViewed`, {burst} calls per run, {burst_runs} runs, pooled) | {len(tm)} | {fmt(stats(tm), 3)} | {fmt(stats(tw), 3)} |

The first `track` call of each burst run (main thread): p50 {median(first_calls):.3f} ms, max {max(first_calls):.3f} ms. `track()` runs on the caller's actor (the main actor here) until it hands the work to the SDK; the numbers are the main-actor part of one call. Samples: `track-samples.csv`.

## Memory (`footprint`, physical footprint of the app process, MiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| {idle}s after start (n={runs}) | {mb(median(fp('nosdk'))):.2f} ({mb(min(fp('nosdk'))):.2f} - {mb(max(fp('nosdk'))):.2f}) | {mb(median(fp('sdk'))):.2f} ({mb(min(fp('sdk'))):.2f} - {mb(max(fp('sdk'))):.2f}) | {mb(median(pd_fp)):+.2f} ({mb(min(pd_fp)):+.2f} to {mb(max(pd_fp)):+.2f}) |
| {idle}s after {burst} `track()` calls (n={burst_runs}) | {mb(median(B('nosdk'))):.2f} ({mb(min(B('nosdk'))):.2f} - {mb(max(B('nosdk'))):.2f}) | {mb(median(B('sdk'))):.2f} ({mb(min(B('sdk'))):.2f} - {mb(max(B('sdk'))):.2f}) | {mb(median(pd_b)):+.2f} ({mb(min(pd_b)):+.2f} to {mb(max(pd_b)):+.2f}) |

The endpoint was `http://127.0.0.1:9` (nothing answers) with a dummy key, so the SDK queued events and its delivery attempts failed, as in an app that is offline; no event left the Mac. The baseline arm's `track()` does nothing. Memory is the Simulator host process view (`footprint -j`), a sample of a moving value: read differences of the order of the spread or more.

Raw data: `size.csv`, `sdk-sections.csv`, `link-objects.csv`, `dependencies.txt`, `startup.csv`, `init.csv`, `track-samples.csv`, `memory-burst.csv`, `meta.json`.
"""
    (out / "summary.md").write_text(summary)
    print(summary)


if __name__ == "__main__":
    {"size": size, "run": run}[sys.argv[1]](*sys.argv[2:])
