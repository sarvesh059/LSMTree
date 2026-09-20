# Benchmark — current state (post T6.2)

Ad-hoc wall-clock benchmarks against the codebase as of T6.2 (streaming compaction) being
complete. Not JMH — a single-process, single-run harness using `System.nanoTime()`, run via
the same `javac`/`java` workflow used throughout development. Numbers are real, measured on
this machine, and will vary on others; treat them as directionally informative, not
precise/reproducible-to-the-percent figures. This is a snapshot for the current, single-threaded,
bloom-filter-less state of the project — not the formal T7.4 deliverable (which should probably
use a proper microbenchmark harness like JMH for statistically rigorous, warmup-controlled results).

## 1. Write throughput

10,000 sequential `put()` calls, 20-byte random values, against a fresh `LSMTree`.

```
entries: 10000, valueSize: 20 bytes
total time: 0.321 s
throughput: 31,146 puts/sec
avg latency per put: 32.1 us
```

This is the fsync-bound number — `put()` calls `wal.fsync()` on every single write (T4.2's
durability guarantee), so this figure reflects the cost of a real fsync to disk on every put,
not an in-memory-only rate. It's the meaningful number to report given the project's durability
model; batching/group-commit would trade some of this durability guarantee for higher throughput,
which isn't something this codebase currently does.

## 2. Point-read latency: hit vs. miss

10,000 entries flushed into a single segment, then 5,000 random reads each for present (hit)
and absent (miss) keys.

```
hit : avg=45.1us   p50=44.0us   p99=85.7us    max=635.7us    (n=5000)
miss: avg=897.8us  p50=876.4us  p99=1228.2us  max=3366.8us   (n=5000)
```

**Misses are ~20x slower than hits on average.** This is a real, notable finding, not noise —
p50 shows the same ~20x gap, so it's not just a handful of slow outliers. I ran a follow-up to
understand why, isolating two kinds of miss:

```
in-range miss (cmp>0 path, no EOF): avg=447.7us  p50=449.3us  (n=5000)
beyond-end miss (EOFException path): avg=881.0us  p50=875.7us  (n=5000)
```

Two things this shows:
- **A miss that runs off the end of the segment and hits `EOFException`** (the case in the
  original hit/miss test above — miss keys were all numerically past the last real key) **is
  roughly 2x slower than a miss that resolves via the normal `cmp > 0` early-return** inside
  `SSTable.scan()`. That's consistent with the known cost of constructing a Java exception
  (stack-trace capture) being used for expected, routine control flow rather than a truly
  exceptional condition — `EOFException` here signals "ran past the last entry while scanning
  a bounded range," which happens on every miss whose key sorts after everything in the segment.
- **Even the "clean" in-range miss (no exception at all) is still ~10x slower than a hit**
  (447.7us vs 45.1us). I don't have a fully confirmed explanation for the full size of that
  base gap from this benchmark alone — a plausible contributor is JIT warmup asymmetry (the
  hit path's `cmp == 0` early-return is exercised far more often across the whole benchmark
  than the miss path's longer scan-and-compare loop, so the miss path may simply be less
  JIT-optimized by the time it's measured), but confirming that would need a proper
  warmup-controlled harness (JMH), not this ad-hoc timer. Flagging as an honest gap in this
  benchmark's rigor rather than asserting a cause I haven't verified.

This result is a concrete, measured motivation for **T7.1 (bloom filter)** — a `get` for a key
absent from a segment currently always pays for a full index scan plus (usually) a multi-entry
data scan; a bloom filter would let it skip the segment in O(1) instead.

## 3. Effect of compaction on read latency

20 segments (500 keys each, disjoint key ranges — oldest segment holds the smallest keys),
3,000 random reads across the full key range, measured before and after an explicit `compact()`.

```
before compaction: segmentCount=20
  before-compact: avg=58.2us  p50=46.7us  p99=327.0us  max=3342.3us  (n=3000)
after compaction:  segmentCount=1
  after-compact : avg=45.3us  p50=43.5us  p99=123.3us  max=538.8us   (n=3000)
```

Compaction reduces average read latency by ~22% here, but the more meaningful effect is on the
**tail**: p99 drops from 327us to 123us (~2.7x), and max drops from 3.3ms to 0.5ms (~6x). This
matches the expected shape — with 20 segments, a key living only in the oldest segment (which
`get()` checks last, per the newest-to-oldest scan order) pays for 19 prior segment lookups
before it's found; after compaction there's only one segment to check regardless of which key
is queried. The average moves less than the tail because most reads in this workload only need
to check a few segments before finding their key.

## 4. Bloom filters

Not measurable yet — T7.1 isn't implemented. Per the reordered Phase 7 plan, T7.1 is next up;
re-running section 2 of this benchmark after it lands would be the natural way to quantify its
effect on miss latency specifically.

## Reproducing (sections 1-3 above)

Ad-hoc harness, not checked into the project (this was a one-off measurement run, not a
permanent benchmark suite) — was compiled and run directly against `src/`, in the `LSMTree`
package (needed for `segmentCount()` access), via the same `javac`+`java` workflow used for
verification throughout development.

---

## 5. YCSB-style baseline (pre-Phase-7)

A standing, reusable harness now lives at `bench/LSMTree/YcsbStyleBenchmark.java` — adopts
YCSB's workload *definitions* (not the YCSB framework itself): a proper Zipfian key generator
(theta=0.99, YCSB's default skew) plus workloads A/B/C/D/F. Workload E (short-range scans) is
omitted until T7.2 (range scan) exists. Each workload loads 50,000 keys, then runs 20,000
operations against that Zipfian-skewed keyspace. Compaction threshold is 10 segments (not
disabled) so results are a stable reference point rather than degrading non-stationarily within
a single run.

**Run this again after each T7 task lands and compare against these numbers:**
```
javac -d out -cp "lib/*" $(find src -name "*.java")
javac -d out -cp out $(find bench -name "*.java")
java -cp out LSMTree.YcsbStyleBenchmark
```

### Baseline results (pre-T7, T6.2 complete)

```
Workload A (50% read / 50% update, Zipfian)
  ops/sec=3737   avg=267.6us  p50=25.4us  p99=510.8us  max=346770.2us

Workload B (95% read / 5% update, Zipfian)
  ops/sec=9397   avg=106.4us  p50=63.8us  p99=366.9us  max=326836.6us

Workload C (100% read, Zipfian)
  ops/sec=28583  avg=35.0us   p50=32.3us  p99=69.1us   max=433.8us

Workload D (95% read-latest / 5% insert)
  ops/sec=20836  avg=48.0us   p50=27.1us  p99=76.3us   max=367794.6us

Workload F (50% read / 50% read-modify-write, Zipfian)
  ops/sec=3027   avg=330.4us  p50=105.6us p99=533.5us  max=368895.0us
```

### Findings

- **Workload C is fastest, D is close behind** — both read-dominated, and D's "read latest"
  pattern benefits from Zipfian-favoring keys that are still in the MemTable or the newest
  segment (no file I/O needed), which is exactly the access pattern LSM-trees are shaped to
  serve well.
- **A and F are the slowest by average/p50** — both have a heavy write component, and every
  `put()` pays for a synchronous, fsync'd WAL append (T4.2's durability guarantee) regardless
  of workload mix.
- **Every workload with writes shows a severe max-latency outlier (~330-370ms) that p99 doesn't
  reflect** (e.g. Workload A: p99=511us vs max=346,770us — a ~680x gap). This is a compaction
  stall: compaction is still fully synchronous (inline with `flush()`), so whichever `put()`
  happens to cross the segment threshold pays the *entire* cost of merging up to 10 segments
  before returning, blocking that one request for hundreds of milliseconds. Workload C (no
  writes, no compaction ever triggered) shows no such spike, confirming the cause. This is a
  concrete, measured case for **T7.6's async-compaction work** — moving that cost off the
  request path, not just adding thread-safety for its own sake.

### Caveats

Same as sections 1-3: single-process wall-clock timing, no JMH-style warmup control, one
machine, one run. Good enough to see *directional* before/after effects across T7 enhancements,
not to make sub-percent precision claims.

---

## 6. Post-T7.5 (order-preserving key encoding) comparison

Same harness, same command, run again after T7.5 landed (`SSTable`'s `scan`/`findFloorSample`/`get`
now resolve entirely via `readRawEncoded`/`compareEncoded` — zero `decode()` calls during a lookup,
confirmed by a dedicated instrumented-codec test, not just by these numbers).

### Results (post-T7.5)

```
Workload A (50% read / 50% update, Zipfian)
  ops/sec=3599  avg=277.9us  p50=26.2us  p99=535.9us  max=364559.8us

Workload B (95% read / 5% update, Zipfian)
  ops/sec=9178  avg=109.0us  p50=65.1us  p99=374.7us  max=338116.7us

Workload C (100% read, Zipfian)
  ops/sec=27528 avg=36.3us   p50=33.3us  p99=73.3us   max=688.2us

Workload D (95% read-latest / 5% insert)
  ops/sec=21387 avg=46.8us   p50=27.8us  p99=76.1us   max=341466.7us

Workload F (50% read / 50% read-modify-write, Zipfian)
  ops/sec=2943  avg=339.8us  p50=107.7us p99=546.7us  max=355319.7us
```

### Delta vs. baseline (section 5)

| Workload | ops/sec | avg | p50 | p99 |
|---|---|---|---|---|
| A | -3.7% | +3.8% | +3.1% | +4.9% |
| B | -2.3% | +2.4% | +2.0% | +2.1% |
| C | -3.7% | +3.7% | +3.1% | +6.1% |
| D | +2.6% | -2.5% | +2.6% | -0.3% |
| F | -2.8% | +2.8% | +2.0% | +2.5% |

### Findings — an honest null result for this benchmark

**No measurable improvement, and the direction isn't even consistent** (D got slightly faster,
A/B/C/F got slightly slower, all within a few percent). This is within normal single-process
wall-clock noise, not a real signal either way — worth saying plainly rather than reading a
story into noise. A few concrete reasons this specific benchmark was never likely to show T7.5's
benefit clearly:

- **`IntegerKeyCodec.decode()` was already cheap** — `ByteBuffer.wrap(...).getInt() ^ MIN_VALUE`
  is a handful of nanoseconds. Avoiding it doesn't move the needle when it's competing against
  disk I/O, fsync latency, and JVM object allocation as the actual dominant costs per operation.
- **This harness only exercises `LSMTree<Integer>`.** T7.5's real payoff is for codecs where
  decoding is genuinely expensive — `StringKeyCodec.decode()` does a full escape/terminator walk
  plus UTF-8 string construction, meaningfully more work than an int XOR. An Integer-only
  benchmark structurally can't surface that difference.
- **Compaction threshold (10 segments) keeps the sparse index short**, so the absolute number of
  comparisons `findFloorSample` saves from skipping decode is small per lookup regardless of codec.

None of this means T7.5 wasn't worth doing — the value is architectural (a real technique used in
production LSM engines), foundational for future work that needs efficient key comparison (T7.3's
leveled compaction, potentially T7.2's range scan), and its actual performance win would need a
`String`-keyed YCSB run to observe. Worth extending the harness to a `LSMTree<String>` variant
before drawing a performance conclusion about T7.5 specifically — noted here as a gap in this
benchmark's coverage, not a verdict on the work.

### Caveats

Same as before — single-process wall-clock timing, one machine, one run, no JMH warmup control.

---

## 7. Post-T7.1 (bloom filter per SSTable) comparison

Same harness, same command, run again after T7.1 landed (`get()` now consults each segment's
bloom filter and `continue`s past any segment reporting the key definitely absent, skipping that
segment's file open + `findFloorSample` + data-file scan entirely).

### Results (post-T7.1, two runs)

```
Run 1:
Workload A (50% read / 50% update, Zipfian)
  ops/sec=4485  avg=223.0us  p50=23.0us  p99=136.1us  max=365454.3us

Workload B (95% read / 5% update, Zipfian)
  ops/sec=19351 avg=51.7us   p50=31.7us  p99=113.5us  max=335973.1us

Workload C (100% read, Zipfian)
  ops/sec=27664 avg=36.1us   p50=33.0us  p99=72.0us   max=1033.4us

Workload D (95% read-latest / 5% insert)
  ops/sec=20656 avg=48.4us   p50=28.5us  p99=75.5us   max=368019.5us

Workload F (50% read / 50% read-modify-write, Zipfian)
  ops/sec=3774  avg=265.0us  p50=51.7us  p99=320.2us  max=408760.5us

Run 2 (confirmation):
Workload A: ops/sec=4409  avg=226.8us  p50=25.1us  p99=140.5us
Workload B: ops/sec=19275 avg=51.9us   p50=31.7us  p99=113.4us
Workload C: ops/sec=27658 avg=36.2us   p50=33.2us  p99=72.6us
Workload D: ops/sec=21188 avg=47.2us   p50=28.2us  p99=76.3us
Workload F: ops/sec=4135  avg=241.9us  p50=47.6us  p99=183.1us
```

Run-to-run variation is a few percent at most — the effect below is a real signal, not noise.

### Delta vs. baseline (section 6, post-T7.5, using run 1)

| Workload | ops/sec | avg | p50 | p99 |
|---|---|---|---|---|
| A | +24.6% | -19.8% | -12.2% | **-74.6%** |
| B | **+110.9%** | -52.6% | -51.3% | -69.7% |
| C | +0.5% | -0.6% | -0.9% | -1.8% |
| D | -3.4% | +3.4% | +2.5% | -0.8% |
| F | +28.2% | -22.0% | -52.0% | -41.4% |

### Findings — a real, substantial win this time (unlike T7.5)

- **Workload B roughly doubles in throughput, and A/F both gain ~25-28%.** All three mix reads
  across a multi-segment tree (the initial 50,000-key load plus in-workload `put()`s keeps
  compaction cycling segments up to the threshold of 10) where a meaningful fraction of `get()`
  calls used to walk backward through several segments — opening each data file, computing a
  floor sample, and scanning to a miss — before finding the key in an older segment, or
  confirming it's absent everywhere. The bloom filter turns most of those per-segment misses into
  a single in-memory `mightContain` check (2 hash computations + k bit lookups), skipping the
  file I/O and scan entirely.
- **p99 drops sharply for A/B/F (-42% to -75%)** — this is exactly the tail benefit you'd expect:
  the *worst* case for the old code was a `get()` that had to walk many/all segments before
  missing everywhere, and that's precisely the case the filter now short-circuits cheaply.
- **Workload C is flat (+0.5%, within noise)** — expected. C is 100% reads with no `put()`s
  during the timed portion, so no new segments are created and no compaction is triggered after
  the initial load's single flush; the tree has one segment for the whole run, and Zipfian favors
  low-rank keys that were all loaded up front, so almost every read is a same-segment hit. With
  nothing to skip, there's nothing for the filter to save.
- **Workload D shows a small regression (-3.4% ops/sec, +3.4% avg)** — the one honest downside.
  D's "read-latest" access pattern means the vast majority of reads hit the *newest* segment on
  the first check (Zipfian-distributed offset-from-the-end strongly favors offset 0). In that
  case the bloom filter adds pure overhead — two FNV-1a hashes plus k bit lookups — for a segment
  that was going to be checked and hit anyway; there's no skipped I/O to pay for it. Small and
  expected, not a red flag: it's the predictable cost of the filter on the one workload shape
  where it has nothing to skip.

Unlike T7.5, this benchmark clearly shows the payoff the feature was built for — LSM-trees pay a
real, measurable cost for negative/older-segment lookups, and a per-segment bloom filter removes
most of it.

### Caveats

Same as before — single-process wall-clock timing, one machine, two runs for stability, no JMH
warmup control. `LSMTree<Integer>` only, `IntegerKeyCodec`'s cheap 4-byte encode keeps hashing
cost low; a `String`-keyed run would show a different (likely still positive, but different
magnitude) balance between filter-hashing cost and I/O saved.

---

## 8. Post-T7.2 (range scan) — Workload E added

T7.2 added `LSMTree.scan(low, high)`, so Workload E (short-range scans), previously skipped for
having nothing to test, is now in the harness. It adapts YCSB's usual "start key + record count"
scan shape to this project's key-range API: a Zipfian-distributed start key, then a uniformly
random scan length in `[1, 100]` (YCSB's own default is 1000; scaled down here), translated to
`scan(start, start + length - 1)` against this benchmark's dense integer keyspace. Each op fully
drains the returned `EntrySource` — an unread cursor wouldn't reflect real work.

A/B/C/D/F are included again as a regression check — `scan()` is a purely additive read path, so
these should be unchanged from section 7's post-T7.1 numbers.

### Results (two runs)

```
Run 1:
Workload A: ops/sec=4571  avg=218.8us  p50=23.2us  p99=135.4us
Workload B: ops/sec=19471 avg=51.4us   p50=31.8us  p99=114.2us
Workload C: ops/sec=28162 avg=35.5us   p50=32.7us  p99=70.2us
Workload D: ops/sec=21700 avg=46.1us   p50=27.4us  p99=74.9us
Workload E: ops/sec=4056  avg=246.5us  p50=245.2us p99=406.3us  max=3340.1us
Workload F: ops/sec=4271  avg=234.1us  p50=46.5us  p99=167.2us

Run 2 (confirmation):
Workload A: ops/sec=4610  avg=216.9us  p50=21.8us  p99=134.8us
Workload B: ops/sec=19258 avg=51.9us   p50=32.0us  p99=113.8us
Workload C: ops/sec=27727 avg=36.1us   p50=33.0us  p99=72.1us
Workload D: ops/sec=21103 avg=47.4us   p50=28.0us  p99=77.9us
Workload E: ops/sec=4035  avg=247.8us  p50=245.8us p99=410.4us  max=3088.8us
Workload F: ops/sec=4229  avg=236.5us  p50=47.3us  p99=164.6us
```

### Findings

- **A/B/C/D/F are unchanged from section 7** (within normal run-to-run noise, a few percent at
  most) — confirms `scan()` didn't regress the existing read/write paths, as expected for an
  additive feature.
- **Workload E's per-op latency is naturally much higher than a point lookup** (avg ~247us,
  p50 ~245us vs. Workload C's ~36us) — expected, since each "op" here is an entire scan, not a
  single key. Average scan length is ~50 entries (uniform 1-100), so ~247us for ~50 entries works
  out to roughly ~5us/entry, plausible given each entry crosses the heap-merge (`MergeCursor`'s
  `PriorityQueue`, O(log(segment count)) per pop/push) on top of the underlying file reads.
- **No compaction-stall tail.** Workload E's max (~3.1-3.3ms) is orders of magnitude below A/B/D/F's
  ~330-345ms max (the known synchronous-compaction stall from section 5, still unfixed pending
  T7.6). Workload E issues zero writes, so compaction never triggers during the timed portion —
  same reasoning as Workload C's flat profile, and further confirmation the stall is specifically
  write-triggered, not something scanning also suffers from.
- **Streaming a range looks meaningfully cheaper than the equivalent number of point lookups**,
  by rough estimate: ~50 independent `get()` calls at Workload C's ~36us average would cost
  ~1.8ms, against Workload E's measured ~247us average for a similarly-sized scan — roughly 7x
  cheaper. This isn't a direct, controlled comparison (different code paths, different call
  patterns), so treat it as a plausibility estimate rather than a precise multiplier, but it's the
  expected shape: a streaming scan pays each segment's seek-to-floor cost once and then walks
  forward, instead of every point lookup separately re-running bloom-filter checks, index floor
  lookups, and file opens from scratch.

### Caveats

Same as before — single-process wall-clock timing, one machine, two runs for stability, no JMH
warmup control. `MAX_SCAN_LENGTH=100` and `keySpace=50,000` are this harness's choices; a
workload with longer scans or a larger/sparser keyspace would shift Workload E's absolute numbers
(though not the qualitative "range scan write-quiescent" conclusion).

---

## 9. T7.3 (size-tiered compaction) — write/read/space amplification

T7.3's AC is different in kind from the throughput/latency benchmarks above — it asks to
characterize a compaction strategy's write- vs. read/space-amplification trade-off, not measure
ops/sec. New harness: `bench/LSMTree/CompactionAmplificationBenchmark.java`, purely byte- and
call-counting, no wall-clock timing at all, and therefore fully deterministic (same seed, same
operation sequence, identical output on every run — verified by running it twice).

The workload is the same shape as Workload A elsewhere in this document: `keySpace=50,000`
sequential-key load, then 20,000 ops of 50% Zipfian-distributed reads / 50% Zipfian-distributed
updates (`theta=0.99`), `memTableThreshold=8192`. `FullCompactStrategy(10)` and
`SizeTieredCompactStrategy(minSegmentsPerTier=4, bucketLow=0.5, bucketHigh=1.5)` run the
*identical* seeded sequence of puts/gets/values (same `Random(42)`, consumed in the same order
regardless of which segment happens to exist at that moment), so the two strategies are directly
comparable, not just similar.

Definitions used:
- **Write amplification** = total bytes written to disk (every flush's output + every
  compaction's output, summed by listing every `dataFile-*` file present at the end of the run —
  this project never physically deletes superseded segment files, so nothing is missed) ÷ total
  bytes the client actually `put()`. WAL bytes are deliberately excluded — WAL behavior is
  identical regardless of compaction strategy, so including it would only dilute the number that
  actually reflects what's being compared.
- **Space amplification** = total live segment bytes (`tree.segments()`, summed) ÷ the true
  fully-deduplicated size, measured by force-merging all currently-live segments via
  `FullLoadMergeStrategy` into a throwaway temp file (independent of whichever strategy is under
  test, since `SizeTieredCompactStrategy.select()` only ever returns one bucket, not everything).
- **Read amplification** = average segments actually consulted per `get()`
  (`segmentsConsultedCount() / getCallCount()`, two counters added directly to `LSMTree.get()`'s
  existing loop — see the discussion in this session on why an external wrapper duplicating the
  bloom-filter-check logic was rejected in favor of instrumenting the real call site).

### Results (two runs, byte-identical both times)

```
FullCompactStrategy
  writeAmp=44.60x  (writtenBytes=302558778, putBytes=6783164)
  spaceAmp=1.00x   (liveBytes=5858565, logicalBytes=5850012)
  readAmp=1.312 segments/get  (segmentsConsulted=13085, getCalls=9972)

SizeTieredCompactStrategy
  writeAmp=4.50x   (writtenBytes=30508359, putBytes=6783164)
  spaceAmp=1.10x   (liveBytes=6434079, logicalBytes=5850012)
  readAmp=2.157 segments/get  (segmentsConsulted=21510, getCalls=9972)
```

### Findings

- **Write amplification: size-tiered is ~10x lower (4.50x vs. 44.60x).** This is the expected,
  defining trade-off — `FullCompactStrategy(10)` rewrites the *entire* live dataset every time
  segment count crosses 10, and that cost compounds repeatedly over a 50,000-key load plus
  20,000 further operations. `SizeTieredCompactStrategy` only ever merges segments within a
  similarly-sized tier, so a given byte gets rewritten roughly once per tier it passes through
  rather than once per full-database merge — dramatically fewer total bytes moved.
- **Read amplification: size-tiered is ~1.6x higher (2.157 vs. 1.312 segments/get).** Also
  exactly the expected direction — full compaction aggressively collapses segment count back down
  after every trigger, so there are usually few segments to check per lookup. Size-tiered
  deliberately lets same-sized tiers coexist without merging across tiers, so more segments
  survive at any given moment, and more of them can plausibly hold a given key.
- **Space amplification is close to parity (1.00x vs. 1.10x), smaller than the other two effects
  but still in the expected direction.** A full compaction is, by construction, driven back to
  the true logical size every time it fires (hence exactly 1.00x). Size-tiered's more conservative
  merging leaves a small amount of superseded/duplicate data sitting in not-yet-merged tiers at
  any snapshot in time — real, but modest at this workload's scale, since Zipfian skew means most
  churn concentrates in a small hot-key set that cycles through tiers relatively quickly.

Taken together: this is the size-tiered vs. leveled trade-off from first principles, reproduced
as measured numbers on this project's own implementation rather than just theory — size-tiered
trades a large, real write-amplification win for a smaller but real read-amplification cost, with
space amplification only mildly affected at this workload's scale.

### Caveats

Deterministic and reproducible (unlike every wall-clock benchmark in this document), but still a
single workload shape, single seed, single set of strategy parameters
(`minSegmentsPerTier=4, bucketLow=0.5, bucketHigh=1.5` for size-tiered;
`compactionThreshold=10` for full compaction) — different parameter choices would shift the exact
ratios, though not the qualitative direction of the trade-off, which follows directly from each
strategy's structure rather than from these specific numbers.

---

## 10. Concurrency baseline, pre-persistent-tree

Captured immediately before starting the persistent (structurally-shared, lock-free) memTable
tree rewrite, specifically to have a real "before" to diff against once it lands. New harness:
`bench/LSMTree/ConcurrencyBenchmark.java` — fixed total op count (50,000) split across a swept
thread count (1/2/4/8/16), each thread with its own seeded RNG/Zipfian generator to avoid a
shared-RNG confound, measuring aggregate wall-clock throughput. Two workload shapes: write-heavy
(50% read / 50% update, Zipfian) and read-only (100% read, Zipfian) — the second specifically to
isolate reader-vs-reader contention with zero writers involved.

### Results

```
Write-heavy (50% read / 50% update, Zipfian)
  threads= 1  totalOps=50000  elapsed=6334ms   ops/sec=7894
  threads= 2  totalOps=50000  elapsed=4619ms   ops/sec=10826
  threads= 4  totalOps=50000  elapsed=5263ms   ops/sec=9500
  threads= 8  totalOps=50000  elapsed=10186ms  ops/sec=4909
  threads=16  totalOps=50000  elapsed=12313ms  ops/sec=4061

Read-only (100% read, Zipfian)
  threads= 1  totalOps=50000  elapsed=2342ms  ops/sec=21349
  threads= 2  totalOps=50000  elapsed=1773ms  ops/sec=28202
  threads= 4  totalOps=50000  elapsed=2010ms  ops/sec=24870
  threads= 8  totalOps=50000  elapsed=2691ms  ops/sec=18582
  threads=16  totalOps=50000  elapsed=2790ms  ops/sec=17920
```

### Findings

- **Both workloads peak at 2 threads, then collapse.** Write-heavy: 7,894 → 10,826 (+37%) at 2
  threads, then down to 9,500, 4,909, 4,061 — 16 threads does *less* useful work per second than
  a single thread. Classic lock-contention thrashing: past a small amount of parallelism, adding
  threads adds pure contention overhead with no additional throughput.
- **Read-only shows the same collapse (28,202 → 17,920 from 2 to 16 threads) despite zero
  writers.** This is the important number — it proves the bottleneck isn't write serialization
  specifically, it's that *every* `get()` call, even in a workload with no writes at all,
  contends with every other concurrent `get()` on `get()`'s brief `synchronized(this)` memTable
  check. Pure readers are already fighting each other for a lock today.
- This gives a concrete target for the persistent-tree work: once memTable reads need no lock at
  all, the read-only workload in particular should scale close to linearly with thread count
  (bounded by actual CPU/cores and I/O, not artificial serialization) instead of collapsing past
  2 threads the way it does now.

### Caveats

Single machine, single run per thread-count point, no JMH warmup control — same caveats as every
other benchmark in this document. `TOTAL_OPS=50,000` fixed across all thread counts (so more
threads means less work per thread, not more total work) — chosen to isolate scaling behavior,
not to represent a realistic sustained load.
