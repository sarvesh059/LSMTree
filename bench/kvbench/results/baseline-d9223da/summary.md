### Environment

```
date: 2026-10-01T17:15:50+00:00
kernel: Linux 6.12.5-linuxkit
cpu: -
cpus_available: 1   pinned_to: none
memory: 7837 MiB
data_dir: /data/kvbench (ext4 on /dev/vda1)
java: openjdk version "25.0.4.1" 2026-08-18   opts: -Xms1g -Xmx1g -XX:+AlwaysPreTouch
gcc: g++ (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0
leveldb: 1.23-5build1   rocksdb: 8.9.1-2
engine_commit: d9223da
runs: 3   extra_args: none
```

### Throughput (ops/sec, median of 3 runs, [min–max])

| Workload | candidate | leveldb | rocksdb | candidate vs best reference |
|---|---|---|---|---|
| Random writes, fsync each (1 thread) | 5,921 [3,458–6,254] | 6,428 [6,408–6,468] | 6,266 [6,155–6,505] | 1.1× slower |
| Random writes, fsync each (8 threads) | 5,968 [5,892–6,065] | 28,717 [25,495–30,439] | 24,549 [23,879–24,678] | 4.8× slower |
| Random writes, no per-write fsync | unsupported | 895,664 [890,364–961,480] | 431,884 [421,669–437,583] | — |
| Point read, key present | 24,680 [24,185–24,919] | 1,259,426 [900,560–1,352,849] | 787,549 [779,755–816,279] | 51.0× slower |
| Point read, key absent | 364,523 [340,246–412,057] | 3,250,663 [1,910,156–3,263,406] | 4,594,788 [4,552,499–4,633,088] | 12.6× slower |
| Range scan, 100 keys (scans/sec) | 1,147 [1,134–1,164] | 132,917 [116,440–134,690] | 58,923 [58,850–58,953] | 115.9× slower |

### Latency (µs, median of runs: p50 / p99 / max)

| Workload | candidate | leveldb | rocksdb |
|---|---|---|---|
| Random writes, fsync each (1 thread) | 154.7 / 321.2 / 145,382 | 148.8 / 263.4 / 10,312 | 153.5 / 258.5 / 9,963 |
| Random writes, no per-write fsync | — | 0.9 / 2.2 / 3,968 | 2.2 / 5.3 / 139 |
| Point read, key present | 40.3 / 75.0 / 15,326 | 0.7 / 1.1 / 30 | 1.2 / 1.6 / 18 |
| Point read, key absent | 0.5 / 43.3 / 5,023 | 0.3 / 0.5 / 11 | 0.2 / 0.8 / 9 |
| Range scan, 100 keys | 856.8 / 1144.8 / 5,215 | 7.5 / 9.2 / 28 | 16.8 / 21.3 / 44 |

### After settle

| | candidate | leveldb | rocksdb |
|---|---|---|---|
| Disk used (MiB) | 12.75 | 11.11 | 11.10 |
| Space amplification | 1.15× | 1.00× | 1.00× |
| Settle time (s) | 0.88 | 0.05 | 0.03 |

### Disk sync floor on this machine

One 120-byte append + fsync: **6,486 ops/sec** (p50 150 µs). With fdatasync: 6,291 ops/sec.
