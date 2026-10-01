#!/usr/bin/env bash
# Shared settings + build step for all kit scripts. Source it; don't run it.
set -euo pipefail
KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${REPO:-$(git -C "$KIT" rev-parse --show-toplevel 2>/dev/null || (cd "$KIT/../.." && pwd))}"
if [[ -z "${SRC:-}" ]]; then
  if [[ -d "$REPO/src/main/java" ]]; then SRC="$REPO/src/main/java"; else SRC="$REPO/src"; fi
fi
DATA_DIR="${DATA_DIR:-/tmp/kvbench-data}"   # must be a real Linux filesystem, not a macOS bind mount
CPUS="${CPUS:-}"                             # e.g. CPUS=0 pins every run to core 0 (taskset)
JAVA_OPTS="${JAVA_OPTS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch}"
JAVA_CP_EXTRA="${JAVA_CP_EXTRA:-}"           # extra jars if the engine gains dependencies
BUILD="$KIT/build"
PHASES="fill_nosync,fill_sync,settle,read_hit,read_miss,scan100,fill_sync_mt"
PIN=(); if [[ -n "$CPUS" ]]; then PIN=(taskset -c "$CPUS"); fi

build() {
  local major
  major=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}')
  if (( major < 22 )); then echo "Need JDK 22+ (found $major): the engine uses unnamed variables (_)." >&2; exit 1; fi
  mkdir -p "$BUILD/classes"
  echo "== building C++ harness"
  g++ -O2 -std=c++20 "$KIT/cpp/kvbench.cc" -o "$BUILD/kvbench" -lrocksdb -lleveldb -lpthread
  echo "== building engine from $SRC + Java harness"
  rm -rf "$BUILD/classes" && mkdir -p "$BUILD/classes"
  # shellcheck disable=SC2046
  javac -nowarn -d "$BUILD/classes" ${JAVA_CP_EXTRA:+-cp "$JAVA_CP_EXTRA"} \
    $(find "$SRC" -name '*.java') "$KIT"/java/LSMTree/*.java
}

engine_cmd() {  # engine_cmd <engine> -> prints nothing, fills CMD array
  case "$1" in
    candidate) CMD=(java $JAVA_OPTS -cp "$BUILD/classes${JAVA_CP_EXTRA:+:$JAVA_CP_EXTRA}" LSMTree.KvBench) ;;
    leveldb|rocksdb) CMD=("$BUILD/kvbench" --engine "$1") ;;
    *) echo "unknown engine $1" >&2; exit 64 ;;
  esac
}
