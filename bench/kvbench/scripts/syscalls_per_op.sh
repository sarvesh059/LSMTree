#!/usr/bin/env bash
# I/O syscalls per operation, per engine, via strace (Linux only; in Docker add --cap-add=SYS_PTRACE).
# Method: strace a baseline run (load + settle), then load + settle + one phase with OPS operations;
# (phase run - baseline) / OPS = syscalls per op. Only fd/file syscalls are counted (-e trace=%file,%desc),
# so JVM thread/GC noise (futex etc.) stays out.
#   OPS=2000 scripts/syscalls_per_op.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
OPS="${OPS:-2000}"
ENGINES=(${ENGINES:-candidate leveldb rocksdb})
OUT="$KIT/results/syscalls-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT" "$DATA_DIR"
command -v strace >/dev/null || { echo "strace not found (apt-get install strace)"; exit 1; }
build
ARGS=(--db "$DATA_DIR" --warmup 0 --hits "$OPS" --misses "$OPS" --scans "$OPS")
for e in "${ENGINES[@]}"; do
  engine_cmd "$e"
  echo "== $e: baseline"
  strace -f -c -e trace=%file,%desc -o "$OUT/$e-base.txt" "${PIN[@]}" "${CMD[@]}" "${ARGS[@]}" --phases fill_sync,settle > /dev/null
  for ph in read_hit read_miss scan100; do
    echo "== $e: $ph"
    strace -f -c -e trace=%file,%desc -o "$OUT/$e-$ph.txt" "${PIN[@]}" "${CMD[@]}" "${ARGS[@]}" --phases "fill_sync,settle,$ph" > "$OUT/$e-$ph.log"
    grep -q VERIFY_FAILED "$OUT/$e-$ph.log" && { cat "$OUT/$e-$ph.log"; exit 1; }
  done
done
rm -rf "$DATA_DIR"
python3 - "$OUT" "$OPS" "${ENGINES[@]}" <<'PY' | tee "$OUT/summary.md"
import sys
from pathlib import Path
out, ops, engines = Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3:]
def counts(p):
    c = {}
    for line in p.read_text().splitlines():
        f = line.split()
        if len(f) >= 5 and f[0].replace('.', '', 1).isdigit() and f[-1] != "total":
            c[f[-1]] = int(f[3])
    return c
print(f"### I/O syscalls per operation ({ops} ops per phase; per scan = one 100-key scan)\n")
print("| Engine | Phase | Syscalls per op | Top syscalls per op |")
print("|---|---|---|---|")
for e in engines:
    base = counts(out / f"{e}-base.txt")
    for ph in ("read_hit", "read_miss", "scan100"):
        run = counts(out / f"{e}-{ph}.txt")
        delta = {k: (run.get(k, 0) - base.get(k, 0)) / ops for k in set(run) | set(base)}
        total = sum(delta.values())
        top = sorted(((v, k) for k, v in delta.items() if v >= 0.05), reverse=True)[:4]
        print(f"| {e} | {ph} | {total:.1f} | " + ", ".join(f"{k} {v:.1f}" for v, k in top) + " |")
PY
echo; echo "Saved to $OUT"
