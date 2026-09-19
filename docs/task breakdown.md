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
- [ ] **T7.2 Range scan.** AC: a merged ascending iterator over memTable + all segments;
      newest wins; tombstones omitted; correct across segment boundaries.
- [ ] **T7.3 Compaction strategy (size-tiered or leveled).** AC: define the trigger and
      show its write- vs read/space-amplification behavior on a workload.
- [ ] **T7.6 Concurrent access safety.** Currently zero synchronization anywhere
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
- [ ] **T7.4 Micro-benchmarks.** AC: report write throughput, point-read latency, and the
      effect of compaction / bloom filters on read cost.

---

### When you want a review
Tell me the **task id** and the **invariant you think you're holding**
(e.g. "T3.3 — a `get` never reads past the target key"). I'll test your code against
that claim and point at *where/why* it breaks, without handing you the fix unless asked.
