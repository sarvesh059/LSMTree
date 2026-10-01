# kvbench — LSMTree vs LevelDB vs RocksDB

Runs one fixed workload against your engine and two production LSM engines, in the same environment,
and writes a markdown summary you can paste into a PR. The full guide (methodology, profiling,
reporting) is shared separately; this file is the quick reference.

Place this directory at `bench/kvbench/` in the LSMTree repo.

## Run (Docker, works on any host)

    docker build -t lsm-bench bench/kvbench
    docker run --rm -it --cpuset-cpus=0 --cap-add=SYS_PTRACE \
        -v "$PWD":/repo -v lsm-bench-data:/data -e DATA_DIR=/data/kvbench -w /repo lsm-bench \
        bench/kvbench/scripts/run_all.sh

## Run (native Linux: Ubuntu 24.04 shown)

    sudo apt-get install -y openjdk-25-jdk-headless g++ librocksdb-dev libleveldb-dev python3 strace
    CPUS=0 bench/kvbench/scripts/run_all.sh

## Scripts

| Script | What it does | Time (1 vCPU) |
|---|---|---|
| `scripts/run_all.sh` | Build, run all engines `RUNS` times (default 3), write `results/<ts>/summary.md` | ~8 min |
| `scripts/syscalls_per_op.sh` | I/O syscalls per get / miss / scan for each engine (strace) | ~5 min |
| `scripts/profile.sh` | Flame graph of one phase of your engine (async-profiler) | ~1 min |

Useful variables: `RUNS`, `CPUS` (taskset core list), `DATA_DIR`, `ENGINES="candidate leveldb"`,
`EXTRA_ARGS="--keys 20000 --hits 20000 --misses 20000 --scans 2000"` (quick smoke run only; never report),
`SRC` (engine sources; auto-detects `src/main/java` or `src`), `JAVA_OPTS`, `PHASE`, `EVENT`, `OPS`.

## Files

- `cpp/kvbench.cc` — reference engines (LevelDB, RocksDB) + disk sync floor probe. Don't edit.
- `java/LSMTree/KvBench.java` — the same workload for your engine. Don't edit the workload.
- `java/LSMTree/CandidateAdapter.java` — the only file you adapt as your engine's API changes.
