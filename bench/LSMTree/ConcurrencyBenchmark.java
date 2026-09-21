package LSMTree;

import compactation.CompactStrategyImpl.FullCompactStrategy;
import compactation.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Throughput under concurrent access, sweeping thread count, for the current locking model
 * (synchronized put/flush/compact, lock-free memTable reads via the persistent RBT, AtomicReference
 * segments).
 *
 * IMPORTANT: the load phase calls {@code awaitCompaction()} after its final flush, so every run
 * starts the timed concurrent phase with a settled, fully-compacted segment count (not whatever
 * async compaction happened to have reached). Without this, segment count at the start of the
 * concurrent phase is non-deterministic (observed anywhere from 1 to 175+ segments across runs),
 * which dominates get() cost far more than any lock and makes thread-count comparisons meaningless
 * -- this was found and fixed after discovering it had silently confounded an earlier baseline.
 *
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.ConcurrencyBenchmark
 */
public class ConcurrencyBenchmark {
    static final double THETA = 0.99;
    static final int VALUE_SIZE = 100;
    static final int KEY_SPACE = 50_000;
    static final int TOTAL_OPS = 50_000;
    static final int[] THREAD_COUNTS = {1, 2, 4, 8, 16};

    public static void main(String[] args) throws Exception {
        System.out.println("=== Write-heavy (50% read / 50% update, Zipfian) ===");
        for (int threads : THREAD_COUNTS) runWorkload(threads, 0.5);

        System.out.println();
        System.out.println("=== Read-only (100% read, Zipfian) ===");
        for (int threads : THREAD_COUNTS) runWorkload(threads, 0.0);
    }

    static void runWorkload(int threadCount, double updateFraction) throws Exception {
        Path dir = Files.createTempDirectory("concurrency-bench");
        IntegerKeyCodec codec = new IntegerKeyCodec();
        LSMTree<Integer> tree = new LSMTree<>(codec, dir, 32, 8192,
                new FullCompactStrategy<>(10), new FullLoadMergeStrategy<>(codec));

        Random loadRng = new Random(42);
        for (int i = 0; i < KEY_SPACE; i++) tree.put(i, randomValue(loadRng));
        tree.flush();
        tree.awaitCompaction(); // settle segment count before timing -- see class javadoc

        int opsPerThread = TOTAL_OPS / threadCount;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(() -> {
                try {
                    Random rng = new Random(1000 + threadIndex);
                    YcsbStyleBenchmark.ZipfianGenerator zipf = new YcsbStyleBenchmark.ZipfianGenerator(KEY_SPACE, THETA, rng);
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = (int) zipf.next();
                        if (rng.nextDouble() < updateFraction) {
                            tree.put(key, randomValue(rng));
                        } else {
                            tree.get(key);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
        }

        for (Thread th : threads) th.start();
        long start = System.nanoTime();
        startLatch.countDown();
        for (Thread th : threads) th.join();
        long elapsedNanos = System.nanoTime() - start;

        if (failure.get() != null) {
            System.out.println("  threads=" + threadCount + " FAILED: " + failure.get());
        } else {
            double opsPerSec = TOTAL_OPS / (elapsedNanos / 1e9);
            System.out.printf("  threads=%2d  totalOps=%d  elapsed=%.0fms  ops/sec=%.0f%n",
                    threadCount, TOTAL_OPS, elapsedNanos / 1e6, opsPerSec);
        }

        tree.close();
    }

    static Value randomValue(Random rng) {
        byte[] b = new byte[VALUE_SIZE];
        rng.nextBytes(b);
        return Value.of(b);
    }
}
