#!/usr/bin/env bash
# Flame graph of exactly one phase of the Java engine, using async-profiler (downloaded on first use).
#   PHASE=read_hit EVENT=cpu  scripts/profile.sh     # where CPU time goes
#   PHASE=read_hit EVENT=wall scripts/profile.sh     # where wall time goes, incl. blocking in syscalls/fsync
#   PHASE=fill_sync_mt EVENT=lock scripts/profile.sh # lock contention between writer threads
# PHASE: fill_sync | read_hit | read_miss | scan100 | fill_sync_mt.  EVENT: cpu | wall | alloc | lock | itimer
# Timings printed during a profiled run are perturbed: never report them.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
PHASE="${PHASE:-read_hit}"
EVENT="${EVENT:-cpu}"
AP_VERSION="${AP_VERSION:-4.5}"
if [[ -z "${ASPROF:-}" ]]; then
  case "$(uname -m)" in x86_64) arch=x64 ;; aarch64|arm64) arch=arm64 ;; *) echo "unsupported arch $(uname -m)"; exit 1 ;; esac
  dir="$KIT/.tools/async-profiler-$AP_VERSION-linux-$arch"
  if [[ ! -x "$dir/bin/asprof" ]]; then
    mkdir -p "$KIT/.tools"
    echo "== downloading async-profiler $AP_VERSION"
    curl -fsSL "https://github.com/async-profiler/async-profiler/releases/download/v$AP_VERSION/async-profiler-$AP_VERSION-linux-$arch.tar.gz" | tar xz -C "$KIT/.tools"
  fi
  ASPROF="$dir/bin/asprof"
fi
build
mkdir -p "$KIT/results" "$DATA_DIR"
OUT="$KIT/results/profile-$(date +%Y%m%d-%H%M%S)-$PHASE-$EVENT.html"
case "$PHASE" in
  fill_sync|fill_sync_mt) phases="$PHASE" ;;
  *) phases="fill_sync,settle,$PHASE" ;;
esac
ASPROF="$ASPROF" "${PIN[@]}" java $JAVA_OPTS -XX:+EnableDynamicAgentLoading -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
  -cp "$BUILD/classes${JAVA_CP_EXTRA:+:$JAVA_CP_EXTRA}" LSMTree.KvBench \
  --db "$DATA_DIR" --phases "$phases" --profile-phase "$PHASE" --profile-event "$EVENT" --profile-out "$OUT"
rm -rf "$DATA_DIR"
echo "Flame graph: $OUT"
