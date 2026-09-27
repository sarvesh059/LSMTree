# LSMTree

A log-structured merge-tree key-value store, built from scratch in Java — no external
storage/database libraries, hand-built red-black tree, WAL, SSTables, bloom filters, and
compaction. The goal wasn't just to satisfy a checklist of acceptance criteria, but to understand
the actual problems production LSM-tree systems (RocksDB, LevelDB, Cassandra) face and how they
solve them — every non-trivial design decision below is grounded in, and often benchmarked
against, how a real system handles the same problem.

## What's in here

- **A hand-built left-leaning red-black tree** (`RBT`), used as the memtable's backing structure.
  Rewritten as a **persistent, path-copying** data structure partway through — each `insert`
  produces a new root sharing every untouched subtree with the old one, published via an atomic
  reference swap, giving lock-free concurrent reads without a full copy-on-write.
- **A write-ahead log** (`WAL`) for durability, replayed on recovery.
- **SSTables** (`SSTable`) — immutable, sorted, on-disk segments with a sparse index and a
  per-segment **bloom filter** to skip segments that provably don't contain a key.
- **Order-preserving key encoding** (`KeyCodec`) — raw byte comparison sorts identically to the
  key type's natural ordering, so range scans and segment range-overlap checks never need to
  decode a key just to compare it.
- **Three pluggable compaction strategies**:
  - `FullCompactStrategy` — rewrites everything past a segment-count threshold. The pathological
    baseline; nothing real uses this at scale, but it's useful as a reference point.
  - `SizeTieredCompactStrategy` — buckets similarly-sized segments and merges within a bucket,
    matching Cassandra's actual STCS defaults. Low write amplification, higher read amplification.
  - `LeveledCompactionStrategy` — full N-level leveled compaction (L0 segments may overlap; L1+
    are strictly non-overlapping), matching RocksDB's real trigger/level-multiplier defaults.
    Higher write amplification than size-tiered, but `get()`/`scan()` exploit the non-overlap
    invariant directly — at most one candidate segment per level for a point lookup, and only
    genuinely overlapping segments get opened for a range scan.
- **A `Version`-based consistency model** — `memTable` and `segments` are bundled into one
  immutable, atomically-published snapshot (the same pattern LevelDB/RocksDB use for their
  `Version`/`VersionSet`), so a `flush()` or `compact()` racing a concurrent `scan()` can never
  produce a torn read.
- **Concurrent access support** — a single writer path (`put`/`flush`/`compact`, deliberately
  serialized) with genuinely lock-free `get()`/`scan()` reads, plus asynchronous, self-resubmitting
  background compaction.

## Project layout

```
src/
  RBT/            persistent left-leaning red-black tree (memtable backing structure)
  memTable/       MemTable wrapper around the RBT
  WAL/            write-ahead log
  SSTable/        on-disk segment read/write, range cursors
  bloomFilter/    FNV-1a double-hashing bloom filter
  compaction/   CompactionStrategy / MergeStrategy and their implementations
  cursor/         merge/range/data-file cursors (k-way heap merge, etc.)
  core/           Value, Segment, IndexEntry and other shared types
  manifest/       manifest log (which segment files are live)
  LSMTree/        top-level orchestration: put/get/scan/flush/compact/recover

test/             JUnit 5 tests, mirroring the src/ package layout
bench/            standalone (non-JUnit) benchmark harnesses
docs/benchmark.md measured results for every non-trivial performance claim made above
```

## Building and running

No build tool (Maven/Gradle) — plain `javac`/`java` against the JUnit jars in `lib/`.

```bash
# compile
javac -d out -cp "lib/*" $(find src -name "*.java")

# run tests (JUnit 5 console launcher, e.g. junit-platform-console-standalone)
javac -d out -cp "lib/*:out" $(find test -name "*.java")
java -jar junit-console.jar execute -cp out --scan-classpath --details=summary

# run a benchmark
javac -d out -cp "lib/*:out" $(find bench -name "*.java")
java -cp out LSMTree.CompactionAmplificationBenchmark
```

## Benchmarks

Every performance claim in this README — bloom filter effect, compaction strategy trade-offs,
concurrency scaling, leveled vs. size-tiered amplification at multiple scales — is backed by a
measured, reproducible run documented in [`docs/benchmark.md`](docs/benchmark.md), including the
methodology, caveats, and a few honest write-ups of results that didn't match the initial
hypothesis until investigated further (e.g. leveled compaction's read-amplification advantage
over size-tiered doesn't show up until the dataset is large enough to populate more than one or
two levels — confirmed, not assumed).
