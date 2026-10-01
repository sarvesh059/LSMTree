#!/usr/bin/env bash
# Build everything, then run every engine RUNS times on the fixed workload.
# Output: results/<timestamp>/{raw.txt,env.txt,summary.md}; summary.md is also printed.
#   RUNS=3 CPUS=0 scripts/run_all.sh
#   EXTRA_ARGS="--keys 20000 --hits 20000 --misses 20000 --scans 2000" scripts/run_all.sh   # quick smoke run
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
RUNS="${RUNS:-3}"
ENGINES=(${ENGINES:-candidate leveldb rocksdb})
EXTRA_ARGS="${EXTRA_ARGS:-}"
OUT="$KIT/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT" "$DATA_DIR"
build

{
  echo "date: $(date -Is)"
  echo "kernel: $(uname -sr)"
  echo "cpu: $(lscpu 2>/dev/null | awk -F: '/Model name/{gsub(/^ +/,"",$2); print $2; exit}')"
  echo "cpus_available: $(nproc)   pinned_to: ${CPUS:-none}"
  echo "memory: $(free -m | awk '/Mem:/{print $2" MiB"}')"
  echo "data_dir: $DATA_DIR ($(df -T "$DATA_DIR" | awk 'NR==2{print $2" on "$1}'))"
  echo "java: $(java -version 2>&1 | head -1)   opts: $JAVA_OPTS"
  echo "gcc: $(g++ --version | head -1)"
  echo "leveldb: $(dpkg-query -W -f='${Version}' libleveldb-dev 2>/dev/null || echo unknown)   rocksdb: $(dpkg-query -W -f='${Version}' librocksdb-dev 2>/dev/null || echo unknown)"
  echo "engine_commit: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)$(git -C "$REPO" diff --quiet 2>/dev/null || echo ' (uncommitted changes)')"
  echo "runs: $RUNS   extra_args: ${EXTRA_ARGS:-none}"
} > "$OUT/env.txt"

for ((r = 1; r <= RUNS; r++)); do
  echo "== run $r/$RUNS"
  "${PIN[@]}" "$BUILD/kvbench" --engine system --db "$DATA_DIR" | sed "s/^RESULT /RESULT run=$r /" | tee -a "$OUT/raw.txt"
  n=${#ENGINES[@]}
  for ((k = 0; k < n; k++)); do            # rotate engine order each run to avoid ordering bias
    e=${ENGINES[$(((k + r - 1) % n))]}
    engine_cmd "$e"
    set +e
    "${PIN[@]}" "${CMD[@]}" --db "$DATA_DIR" --phases "$PHASES" $EXTRA_ARGS > "$OUT/$e-run$r.log" 2>&1
    rc=$?
    set -e
    sed -n "s/^RESULT /RESULT run=$r /p; s/^INFO /INFO run=$r /p" "$OUT/$e-run$r.log" | tee -a "$OUT/raw.txt"
    if (( rc != 0 )); then
      echo "!! $e exited with code $rc — see $OUT/$e-run$r.log" >&2
      grep -h VERIFY_FAILED "$OUT/$e-run$r.log" >&2 || tail -20 "$OUT/$e-run$r.log" >&2
      exit 1
    fi
  done
done
rm -rf "$DATA_DIR"
python3 "$KIT/scripts/summarize.py" "$OUT" | tee "$OUT/summary.md"
echo
echo "Saved to $OUT"
