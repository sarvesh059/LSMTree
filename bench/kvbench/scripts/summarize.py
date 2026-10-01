#!/usr/bin/env python3
"""Turn results/<ts>/raw.txt into a markdown summary (medians over runs). Usage: summarize.py <results dir>"""
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ENGINES = ["candidate", "leveldb", "rocksdb"]
PHASES = [
    ("fill_sync", "Random writes, fsync each (1 thread)"),
    ("fill_sync_mt", "Random writes, fsync each (8 threads)"),
    ("fill_nosync", "Random writes, no per-write fsync"),
    ("read_hit", "Point read, key present"),
    ("read_miss", "Point read, key absent"),
    ("scan100", "Range scan, 100 keys (scans/sec)"),
]


def fmt(x):
    return f"{x:,.0f}" if x >= 100 else f"{x:,.1f}"


def main(outdir: Path):
    rows = defaultdict(lambda: defaultdict(list))   # (engine, phase) -> field -> [values]
    unsupported = set()
    runs = set()
    for line in (outdir / "raw.txt").read_text().splitlines():
        if not line.startswith("RESULT "):
            continue
        kv = dict(re.findall(r"(\w+)=([^\s]+)", line))
        runs.add(kv.get("run"))
        key = (kv["engine"], kv["phase"])
        if line.rstrip().endswith("unsupported"):
            unsupported.add(key)
            continue
        for f, v in kv.items():
            if f not in ("run", "engine", "phase"):
                rows[key][f].append(float(v))

    med = lambda k, f: statistics.median(rows[k][f]) if rows[k].get(f) else None
    out = []
    env = outdir / "env.txt"
    out.append("### Environment\n\n```\n" + (env.read_text().strip() if env.exists() else "(no env.txt)") + "\n```\n")

    out.append(f"### Throughput (ops/sec, median of {len(runs)} runs, [min–max])\n")
    out.append("| Workload | " + " | ".join(ENGINES) + " | candidate vs best reference |")
    out.append("|---|" + "---|" * (len(ENGINES) + 1))
    for phase, label in PHASES:
        cells, best_ref, cand = [], None, None
        for e in ENGINES:
            k = (e, phase)
            if k in unsupported:
                cells.append("unsupported")
                continue
            m = med(k, "ops_per_sec")
            if m is None:
                cells.append("—")
                continue
            vals = rows[k]["ops_per_sec"]
            cells.append(f"{fmt(m)} [{fmt(min(vals))}–{fmt(max(vals))}]" if len(vals) > 1 else fmt(m))
            if e == "candidate":
                cand = m
            else:
                best_ref = m if best_ref is None else max(best_ref, m)
        if cand and best_ref:
            ratio = best_ref / cand
            gap = f"{ratio:.1f}× slower" if ratio >= 1 else f"{1 / ratio:.1f}× faster"
        else:
            gap = "—"
        out.append(f"| {label} | " + " | ".join(cells) + f" | {gap} |")

    out.append("\n### Latency (µs, median of runs: p50 / p99 / max)\n")
    out.append("| Workload | " + " | ".join(ENGINES) + " |")
    out.append("|---|" + "---|" * len(ENGINES))
    for phase, label in PHASES:
        if phase == "fill_sync_mt":
            continue
        cells = []
        for e in ENGINES:
            k = (e, phase)
            p50, p99, mx = med(k, "p50_us"), med(k, "p99_us"), med(k, "max_us")
            cells.append("—" if p50 is None else f"{p50:.1f} / {p99:.1f} / {mx:,.0f}")
        out.append(f"| {label.replace(' (scans/sec)', '')} | " + " | ".join(cells) + " |")

    out.append("\n### After settle\n")
    out.append("| | " + " | ".join(ENGINES) + " |")
    out.append("|---|" + "---|" * len(ENGINES))
    logical = next((med(k, "logical_mb") for k in rows if k[1] == "settle"), None)
    out.append("| Disk used (MiB) | " + " | ".join(
        "—" if med((e, "settle"), "disk_mb") is None else f"{med((e, 'settle'), 'disk_mb'):.2f}" for e in ENGINES) + " |")
    if logical:
        out.append("| Space amplification | " + " | ".join(
            "—" if med((e, "settle"), "disk_mb") is None else f"{med((e, 'settle'), 'disk_mb') / logical:.2f}×" for e in ENGINES) + " |")
    out.append("| Settle time (s) | " + " | ".join(
        "—" if med((e, "settle"), "seconds") is None else f"{med((e, 'settle'), 'seconds'):.2f}" for e in ENGINES) + " |")

    f1, f2 = med(("system", "fsync_floor"), "ops_per_sec"), med(("system", "fdatasync_floor"), "ops_per_sec")
    if f1:
        out.append(f"\n### Disk sync floor on this machine\n\nOne 120-byte append + fsync: **{fmt(f1)} ops/sec** "
                   f"(p50 {med(('system', 'fsync_floor'), 'p50_us'):.0f} µs). With fdatasync: {fmt(f2)} ops/sec.")
    print("\n".join(out))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
