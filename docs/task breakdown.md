# LSM-tree build plan — tasks & acceptance criteria

Small, independently verifiable tasks. **Rule of thumb:** one task ≈ one commit;
write the test that encodes the acceptance criterion (AC) first; don't start a new
phase until the current phase's ACs are green.

Key scoping insight: **the memTable never deletes a node** — a delete is a `put`
of a tombstone. So Red-Black *deletion* is optional; you only need insert + update.

---

## Phase 0 — Scaffolding ✅
- [x] **T0.1 Build & run.** AC: `javac *.java && java Main` runs; an empty `Main` exits 0.
- [x] **T0.2 Test harness.** AC: a failing assertion exits non-zero and names the case.
      (JUnit, or a 20-line assert helper — your call.) Every later task adds ≥1 test.

## Phase 1 — Red-Black Tree (generic ordered map `<K extends Comparable, V>`)
- [x] **T1.1 BST insert + in-order traversal (no balancing).** AC: in-order of inserted
      keys is strictly ascending; `get` returns the inserted value; inserting a duplicate
      key **overwrites the value** (no second node); `size()` counts distinct keys.
- [x] **T1.2 Rotations.** AC: a left rotation followed by the matching right rotation
      returns the identical tree; in-order sequence is unchanged by any rotation.
- [x] **T1.3 RB insert (recolor + rebalance).** AC: after inserting a randomized
      permutation of N=10k keys, an invariant checker passes — (a) root is black,
      (b) no red node has a red child, (c) every root→NIL path has equal black-height,
      (d) in-order is sorted — and measured height ≤ 2·log₂(N+1).
- [x] **T1.4 get / update / size.** AC: `get(absent) == null`; updating a key changes
      only its value; `size()` stays correct across inserts and updates.
- [x] **T1.5 Ordered + range iteration, floor/ceiling.** AC: iterator yields ascending
      keys; `range(lo, hi)` is correct at inclusive/exclusive edges, for an empty range,
      and when lo/hi fall between existing keys. (Needed for flush and range scans.)
- [x] **T1.6 (Optional) RB delete.** *Not required for the LSM memTable.* If attempted,
      AC: invariants (T1.3) hold after an interleaved insert/delete workload.

## Phase 2 — Value & MemTable (backed by *your* RBT)
- [x] **T2.1 Value type.** AC: a tombstone is distinguishable from an empty-string value;
      round-trips through your serialization unchanged.
- [x] **T2.2 MemTable on the RBT.** AC: `put`/`get`/update work; `delete` stores a
      tombstone (not a node removal); an approximate byte-size counter tracks inserts and
      **adjusts on overwrite**; `entries()` yields sorted key→value ready to flush;
      `isFull()` trips at the threshold.

## Phase 3 — SSTable (immutable sorted segment)
- [x] **T3.1 Data format + writer + readAll.** AC: `readAll(write(map)) == map`, including
      tombstones; on-disk entries are strictly ascending by key.
- [x] **T3.2 Sparse index (write + load).** AC: index entry count ≈ ⌈n / sampleEvery⌉;
      the first key is always sampled; each loaded offset seeks to exactly that key.
- [x] **T3.3 Point `get` via sparse index.** AC: returns correct value / tombstone /
      absent. Prove the scan is bounded: for a key between samples it starts at the floor
      sample and stops at the first key > target (entries read ≤ sampleEvery). Cover:
      key < first, key > last, exact sample key, key between samples, tombstoned key.

## Phase 4 — WAL & durability
- [x] **T4.1 Append + replay.** AC: replaying into a fresh memTable reproduces the exact
      key→value state; a truncated trailing record (simulated) is skipped without error.
- [x] **T4.2 fsync + write-path ordering.** AC (code review + crash sim): the WAL is
      appended (and fsync'd) **before** the memTable is updated, and reset **only after**
      the SSTable is fsync'd. Sim: write keys, hard-stop before any flush, reopen → present.

## Phase 5 — LSMTree.LSMTree orchestration
- [x] **T5.1 Single-segment flush.** AC: crossing the threshold flushes; afterwards the
      memTable is empty and the WAL truncated; the flushed keys read back from the SSTable.
- [x] **T5.2 Multi-segment read path.** AC: segments checked newest→oldest, first hit
      wins; an update in a newer segment shadows the older value; a tombstone in a newer
      segment hides an older value (`get` → null).
- [x] **T5.3 Recovery on open.** AC: reopening loads all segments (newest-first) and
      replays the WAL; every key written before close returns its correct latest
      value/deletion.

## Phase 6 — Compaction
- [x] **T6.1 Full compaction.** AC: triggers past the segment threshold; result is a
      single segment; the latest value per key is preserved; tombstoned keys are gone;
      on-disk size shrinks vs pre-compaction; read results are unchanged.
      (Note: "on-disk size shrinks" verified as the merged segment being smaller than the
      sum of what it replaced — physical deletion of superseded segment files is a
      separate, not-yet-built concern; they persist on disk, only logically retired.)
- [x] **T6.2 (Stretch) Streaming k-way merge.** AC: same output as T6.1 but peak memory
      is independent of dataset size (merge via per-segment iterators, not load-all).

## Phase 7 — Real-world extensions (pick any)
Build order below is dependency/synergy-driven, not numeric: T7.5 first since key
comparison is foundational and everything else should build on it rather than retrofit
it later; T7.1 next as an isolated, self-contained win; T7.2 while T6.2's per-segment
iterator/heap-merge machinery is still fresh (range scan is the same shape of problem,
for reads instead of compaction); T7.3 after T7.5 so leveled compaction's range-overlap
checks can use byte comparison; T7.6 near the end so concurrency is designed once against
a stable feature surface (and against T7.3's final compaction shape) instead of being
redone after each subsequent feature; T7.4 last since its own AC needs T7.1 and T7.3 to
exist to have something to measure, and should reflect the final, concurrent-safe system.

- [x] **T7.5 Order-preserving key encoding.** Replace decode-then-compare with raw byte
      comparison for `KeyCodec` implementations. AC: encoded bytes sort (via unsigned
      lexicographic comparison) in the same order as `K.compareTo()`, for both positive
      and negative values. Covers: two's-complement sign-bit handling for signed integer
      types (naive `writeInt` sorts negatives after positives under unsigned byte
      comparison), unsigned vs. signed byte comparison in Java (`byte` is signed —
      comparisons need `b & 0xFF` masking), and safe encoding of variable-length keys
      (e.g. strings) without a raw length prefix breaking lexicographic order.
- [x] **T7.1 Bloom filter per SSTable.** AC: never a false negative; false-positive rate
      within ~2× of target for the chosen bits/hashes; a `get` provably skips a segment
      whose filter reports "absent."
- [x] **T7.2 Range scan.** AC: a merged ascending iterator over memTable + all segments;
      newest wins; tombstones omitted; correct across segment boundaries.
- [x] **T7.3 Compaction strategy (size-tiered or leveled).** AC: define the trigger and
      show its write- vs read/space-amplification behavior on a workload.
- [x] **T7.6 Concurrent access safety.** Currently zero synchronization anywhere
      (`RBT`/`MemTable` mutation, the `memTable` field swap on flush, WAL/Manifest file
      writes, `segments` list mutation) — built and tested single-threaded only. AC:
      concurrent `put`/`get`/`flush` from multiple threads never corrupts the RBT
      structure, never loses/duplicates a WAL or manifest record (no interleaved writes
      to either file), `get` never observes a stale `memTable` reference indefinitely
      (a proper happens-before edge on the flush-time swap), and `segments` mutation is
      safe against concurrent iteration. Likely shape: serialize the WAL-append +
      memTable-mutate path (single writer lock or dedicated writer thread), seal the
      memTable at flush time and publish the fresh one + append to `segments` under that
      same lock/a `volatile`/concurrent collection, so readers never need to lock the
      RBT itself. Also covers **async compaction**: running compaction on a background
      executor rather than inline with `flush()` needs (a) a lock around `Manifest.append`
      shared by both the flush path and the compaction path (their appends can now race —
      TOCTOU on `seek(length())` then `write`), (b) a lock/safe-publication for the
      `segments` swap (remove superseded, add new) so `get()` sees it via a real
      happens-before edge, (c) an in-progress guard so a second compaction isn't submitted
      against segments already mid-merge, and (d) a decision on the old-segment-file
      deletion race: a reader that grabbed a `Segment` reference before the swap can still
      have its `dataFile` deleted out from under it (needs refcounting/pinning to close
      properly, or an accepted documented gap for now). Compaction's snapshot of "which
      segments to merge" must be captured synchronously before submitting to the
      executor — the background task must never re-derive it from live `segments` state.
- [x] **T7.4 Micro-benchmarks.** AC: report write throughput, point-read latency, and the
      effect of compaction / bloom filters on read cost. `docs/benchmark.md` §1-§4 attempted this
      early but went stale (measured before T7.5/T7.1/T7.3/the `Version` work — §4 literally says
      "not measurable yet"); §12 re-measures write throughput and point-read latency against
      current code as two new permanent harnesses (`WriteThroughputBenchmark`,
      `PointReadLatencyBenchmark`), and points at the already-current §7/§9 for the
      compaction/bloom-filter-effect half rather than re-deriving it. Found and fixed a real
      regression while wiring this up: the `Version` refactor had deleted the package-private
      `segments()` accessor `CompactionAmplificationBenchmark.java` depends on, silently breaking
      that harness's compile with nothing catching it (bench files aren't part of the JUnit suite)
      — restored the accessor.

## Side Quests (optional, not required by any phase's AC)

- [ ] **In progress — Leveled compaction strategy.** T7.3's AC was satisfied by size-tiered
      ("pick any"); this is bonus scope, not required. Deliberately deferred until after T7.6 —
      building it first would mean designing its range-overlap-aware trigger/select logic under
      single-threaded assumptions, then needing to revisit it for concurrency safety once T7.6
      lands. Scope committed (full N-level, real read-path payoff, not a toy). Progress so far:
      - [x] `Segment<K>` gains `level` (int) and `minKey`/`maxKey` (`byte[]`, encoded — ready for
        `compareEncoded` without re-encoding) fields. **Not persisted to the segment header** —
        deliberately reconsidered and reversed from the original plan (see below).
      - [x] `MergeStrategy.merge()`'s return type widened to `List<Segment<K>>`; signature also
        widened to accept a `Supplier<SegmentFiles>` factory instead of three fixed `File`s, so a
        strategy can request as many output-file triples as it needs. `LSMTree` owns the factory
        (naming convention + WIP-manifest-registration unchanged, just made repeatable per call);
        `sharedCompactionLogic()` loops over N returned segments (rename-from-WIP, manifest-append,
        add-to-`Version`) instead of assuming exactly one. `FullLoadMergeStrategy`/
        `StreamingMergeStrategy` adapted (call the factory once, since they only ever produce one
        segment). Full project compiles and all tests pass as one unit again (179/179).
      - [x] `LeveledCompactionStrategy`: trigger/select logic built and verified via targeted probes
        — five real bugs found and fixed along the way (missing cross-level overlap inclusion in
        both the L0 and L1+ branches, whole-level-instead-of-one-segment selection, L0's phantom
        byte-size trigger path, an NPE when the next level has no segments yet, a non-monotonic
        compact-pointer sweep from unsorted segments, and a maxKey range computed from the wrong
        field — twice, same class of typo in two different places).
      - [x] `LeveledMergeStrategy`: level tagging (`min(input levels) + 1`) and per-merge
        `minKey`/`maxKey` computed as the union of input ranges (min of mins, max of maxes) —
        verified this is always safe-or-exact, never under-estimates, by induction: `flush()`'s
        base case is exact (tombstones are never elided on a raw flush), and taking the union
        across repeated merges can only match-or-widen the true range, never narrow past it. One
        real bug found (comparing the wrong field for the max computation — same class of typo as
        one of the `LeveledCompactionStrategy` bugs above) and fixed.
      - [x] `flush()` tags new segments `level=0` and sets exact `minKey`/`maxKey` from
        `memTableEntries` (exact, not just a safe bound, since nothing is elided on a raw flush).
      - **Reconsidered: `minKey`/`maxKey` are NOT persisted to the segment header.** The original
        plan (same principle as `maxEventId`, in the header, read back by `recover()`) would have
        touched five separate read call sites (`SSTable.readAll`/`rangeCursor`, `DataFileCursor`,
        `RangeCursor`, `LSMTree.recover()`) — the exact shape of gap that caused 14 failing tests
        the first time a header field was added. Reconsidered because the values are derivable
        cheaply enough not to need it: every other place a `Segment` is built already computes
        `minKey`/`maxKey` once from entries already in hand and just keeps them in memory (same
        pattern as `loadedIndex`/`bloomFilter` — computed once, cached, never re-read from disk).
        The only place that needs to *derive* rather than carry forward is `recover()`: `minKey`
        is free (first entry, right after the header); `maxKey` is a bounded scan from the sparse
        index's last sample to EOF (at most `sampleEvery` entries), not a full scan — unlike
        `entryCount`/`maxEventId`, which genuinely can't be bounded this way (entries are sorted by
        key, not write order, so finding the max event id without persisting it means scanning
        every entry). Still open: wiring this derivation into `recover()`.
      - [ ] Still open: `LeveledMergeStrategy` doesn't yet partition its output into multiple
        bounded-size segments — it writes exactly one segment per merge (the file-factory plumbing
        above is the prerequisite for this and is now in place; the partitioning loop itself, and
        the target-file-size parameter it needs, aren't built yet).
      - [ ] Still open: **`get()`/`scan()` becoming level-aware** — the part that makes the whole
        thing real rather than theoretical: without it, leveled compaction pays its write-amp cost
        with no read-amp payoff to show for it. L0 stays a bloom-filter-gated linear scan,
        newest-to-oldest, same as today. L1+ should jump straight to the *one* segment whose
        `minKey`/`maxKey` range covers the target key, instead of scanning the level.

**Deepening T7.6 toward production grade** (not new features — the same concurrency/async-compaction
ground T7.6 already covers, taken further than its written AC required):

- [ ] **Concurrent (multi-job) compaction.** T7.6 Slice B's async compaction runs one compaction
      at a time on a single-threaded executor — correct, but real systems (RocksDB) run several
      compactions concurrently across the tree as long as their input segment sets don't overlap.
      Needs a thread pool plus tracking *which files are currently being compacted*, so two jobs
      never contend for the same input. Only pays off for a strategy whose buckets are genuinely
      independent (`SizeTieredCompactStrategy`) — `FullCompactStrategy` always selects everything,
      so it can never meaningfully parallelize with itself.
- [ ] **Physical segment-file deletion + refcounting.** True since T6.1 and unchanged by T7.6:
      every segment file this project has ever written stays on disk forever. Needs refcounted
      `Segment`s — a reader pins whatever reference it grabbed; a file only gets deleted once
      nothing live still references it (the same mechanism as LevelDB/RocksDB's `Version`). The
      most invasive of this group — touches `get()`/`scan()`'s hot path directly with pin/unpin
      discipline, not just the compaction subsystem. Scope together with the "MVCC: sequence-
      ceiling filtering + compaction awareness of live snapshots" item below — this is the
      deletion path that item's live-snapshot tracking would actually be protecting against.
- [ ] **Write backpressure / stall when compaction falls behind.** Nothing currently slows writes
      down if compaction can't keep up with the write rate — segments just accumulate and read
      amplification degrades without bound. Real systems deliberately stall/throttle writes when
      compaction falls behind (RocksDB's "write stall" — a graduated slowdown, then a hard stop).
- [ ] **Compaction observability.** No way currently to ask the tree how many compactions are
      pending, how long the last one took, or how far behind compaction is — needed before this
      could be trusted operationally.
- [ ] **Backoff on repeated compaction failure.** A compaction that fails (e.g. disk full) just
      fails again identically next time `flush()` triggers one — no circuit breaker.

**Beyond T7.6 — consistency model & lock-free reads** (new capabilities, not required by any
existing AC; found by asking what consistency guarantees this tree actually offers today):

- [x] **Persistent (immutable, structurally-shared) memTable tree.** In-place RBT mutation
      replaced with path-copying: each `put()` produces a new root sharing every untouched subtree
      with the old one (only the O(log n) nodes on the insertion path get copied), published via
      `RBT`'s internal `AtomicReference<Node<K,V>>` swap — the same pattern already used for
      `segments`. `get()`/`scan()`'s `synchronized` block around the memTable read is gone;
      memTable reads are now genuinely lock-free, and old tree versions stay valid and untouched
      for as long as anything still references them. Two real corruption/correctness bugs found
      and fixed along the way, both with permanent regression tests: (1) rotations/`flipColors`
      were mutating shared, un-cloned sibling nodes in place, corrupting older tree snapshots the
      moment a rotation fired (`RBTPersistenceTest`); (2) `MemTable.put()`'s size accounting raced
      under concurrent writers two separate ways — a same-key delta computed from a stale
      pre-insert read (fixed by `RBT.insert()` atomically returning the value it replaced), and the
      `sizeInBytes` counter update itself being a non-atomic `int +=` (fixed via `AtomicInteger`)
      (`MemTableConcurrencyTest`). `put()` deliberately stays `synchronized` — confirmed via a
      probe that `WAL.append()` has no internal thread-safety and silently corrupts under
      concurrent writers, plus a separate found-but-unfixed race where a writer could land in a
      memtable `flush()` has already retired. Foundational for the MVCC snapshot item below.
- [ ] **Convert MemTable to a skip list.** A different, complementary rung on the same scalability
      ladder rather than a replacement for the item above: this is what RocksDB and LevelDB
      actually use for their memtable — a lock-free (or lightly-locked) skip list supporting a
      single writer with concurrent lock-free readers via careful memory-ordering (`volatile`/CAS
      on forward pointers), without full path-copying or per-write snapshot versions. Expected
      O(log n) search/insert like the RBT, but simpler concurrent-write support and no rebalancing
      (no rotations/recoloring at all — a skip list keeps its expected balance probabilistically,
      via randomized node height, instead of rigid invariants). Worth doing specifically to compare
      against the persistent-RBT path: skip list trades away per-write MVCC snapshots (a skip list
      mutated in place can't cheaply hand out "the tree as of sequence N" the way path-copying can)
      for less allocation churn and a simpler concurrency story — a genuine, real production
      trade-off between the two designs, not just "which is faster."
- [x] **Consistent point-in-time reads for `scan()` (atomic `Version` bundling).** Confirmed real
      via a concrete concurrent probe, not hypothetical: `scan()` used to read `segments` and
      `memTable` as two *separately* captured views, not one atomic snapshot. A key could fall into
      the gap between an in-flight `flush()`'s segment-swap and its memTable-clear and vanish from
      that one scan entirely, even though it existed continuously from the writer's perspective.
      Traced through why a sequence-number ceiling alone (the original plan) would NOT have fixed
      this: `flush()` writes `segments` then `memTable` in that order, and `scan()` read them in
      the *same* order — meaning it could straddle the boundary and see the old segments list *and*
      the new, already-cleared memTable, the worst combination, regardless of any id filter applied
      afterward. A ceiling filters *which writes count*, not *which torn combination of fields you
      happened to read* — a self-consistent filter over a torn read is still torn. Fixed the same
      way LevelDB/RocksDB do for this exact reason: `memTable` + `segments` bundled into one
      `Version` record, published via a single `AtomicReference<Version<K>>`, swapped atomically as
      one unit on every `flush()`/`compact()`. `get()`/`scan()`/`put()` all capture one `Version`
      once per call. Two real bugs found and fixed while landing this, both with permanent
      regression coverage: (1) a first attempt kept `segments` as an `AtomicReference` *inside*
      `Version`, reusing the same mutable cell across transitions instead of a fresh one — proven
      via a reflection-based probe showing an already-captured `Version`'s segment count changing
      after a later, unrelated `flush()`; (2) `recover()` replayed the WAL into one `MemTable`
      object but published a *different*, still-empty one — proven via a close-without-flush /
      reopen probe showing complete silent data loss on recovery. Permanent test:
      `LSMTreeConcurrencyTest.scanNeverLosesAKeyToAConcurrentFlush`.
- [ ] **MVCC: sequence-ceiling filtering + compaction awareness of live snapshots.** Deliberately
      split from the item above after tracing through what each mechanism actually buys: this half
      (filter every source to `id <= ceiling`, compaction tracks the oldest sequence any open
      snapshot still needs before discarding a superseded version) only has something to protect
      once something can actually discard data out from under an old, already-open snapshot. Today
      nothing does — there's no physical segment-file deletion yet, so an old snapshot's captured
      segments/files just sit there untouched regardless. Building this now would compile, pass
      tests, and provably do nothing. Scope it together with physical segment-file deletion +
      refcounting below — in LevelDB/RocksDB these aren't separable mechanisms; `Version`
      refcounting is simultaneously the read-consistency story and the deletion-safety story.
- [ ] **Group commit (batched WAL writes).** Every `put()` currently pays for its own WAL append
      + `fsync` under the single global writer lock. RocksDB's `WriteThread` pattern: one thread
      becomes a leader, batches up whatever other writers are waiting, and does one combined WAL
      write + one `fsync` for all of them before releasing everyone. A pure write-path throughput
      optimization, independent of the other two items in this group.

---

### When you want a review
Tell me the **task id** and the **invariant you think you're holding**
(e.g. "T3.3 — a `get` never reads past the target key"). I'll test your code against
that claim and point at *where/why* it breaks, without handing you the fix unless asked.
