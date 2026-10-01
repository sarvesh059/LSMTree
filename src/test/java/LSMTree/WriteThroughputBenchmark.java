package LSMTree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * T7.4 AC: write throughput. Re-measures docs/benchmark.md section 1 against the CURRENT
 * codebase -- that section's 31,146 puts/sec number predates order-preserving key encoding
 * (T7.5), bloom filters (T7.1), size-tiered compaction (T7.3), and the persistent-tree/Version
 * work (T7.6 deepening), so it's stale for exactly what this AC is asking about.
 *
 * memTableThreshold is set far above the total data written, so no flush() happens during the
 * timed run -- this isolates the put() path itself (WAL append + fsync + memTable insert), not
 * flush/compaction cost, matching the original section 1's "fsync-bound number" framing. put()
 * calls wal.fsync() on every write (T4.2's durability guarantee), so this is a real fsync-to-disk
 * number, not an in-memory-only rate.
 *
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.WriteThroughputBenchmark
 */
public class WriteThroughputBenchmark {
    static final int ENTRIES = 10_000;
    static final int VALUE_SIZE = 20;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("write-throughput-bench");
        IntegerKeyCodec codec = new IntegerKeyCodec();
        LSMTree<Integer> tree = new LSMTree<>(codec, dir, 32, Integer.MAX_VALUE,
                new FullCompactStrategy<>(10), new FullLoadMergeStrategy<>(codec));

        Random rng = new Random(42);
        long[] latenciesNanos = new long[ENTRIES];

        long start = System.nanoTime();
        for (int i = 0; i < ENTRIES; i++) {
            byte[] value = new byte[VALUE_SIZE];
            rng.nextBytes(value);
            long t0 = System.nanoTime();
            tree.put(i, Value.of(value));
            latenciesNanos[i] = System.nanoTime() - t0;
        }
        long totalNanos = System.nanoTime() - start;

        Arrays.sort(latenciesNanos);
        double totalSeconds = totalNanos / 1e9;
        double throughput = ENTRIES / totalSeconds;
        double avgUs = average(latenciesNanos) / 1000.0;
        double p50Us = latenciesNanos[ENTRIES / 2] / 1000.0;
        double p99Us = latenciesNanos[(int) (ENTRIES * 0.99)] / 1000.0;
        double maxUs = latenciesNanos[ENTRIES - 1] / 1000.0;

        System.out.println("entries: " + ENTRIES + ", valueSize: " + VALUE_SIZE + " bytes");
        System.out.printf("total time: %.3f s%n", totalSeconds);
        System.out.printf("throughput: %,.0f puts/sec%n", throughput);
        System.out.printf("latency: avg=%.1fus  p50=%.1fus  p99=%.1fus  max=%.1fus%n", avgUs, p50Us, p99Us, maxUs);
        System.out.println("segmentCount at end (should be 0 -- no flush should have happened): " + tree.segmentCount());

        tree.close();
    }

    static double average(long[] values) {
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.length;
    }
}
