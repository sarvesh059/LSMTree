package LSMTree;

import compactation.CompactStrategyImpl.FullCompactStrategy;
import compactation.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * A standing, reusable benchmark harness modeled on YCSB's workload definitions (not the YCSB
 * framework itself) -- a Zipfian key generator plus workloads A/B/C/D/F, so results can be
 * compared across successive T7 enhancements over time. Re-run after each T7 task lands and
 * diff against the previous run's numbers in docs/benchmark.md.
 *
 * Workload E (short-range scans) is intentionally omitted -- this project has no range-scan
 * operation until T7.2 lands. Add it here once T7.2 exists.
 *
 * Standalone program, not JUnit -- compile/run the same way as the rest of the project:
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.YcsbStyleBenchmark
 */
public class YcsbStyleBenchmark {
    static final double THETA = 0.99; // YCSB's default Zipfian skew
    static final int VALUE_SIZE = 100; // YCSB's default value size
    static final Random RNG = new Random(42);

    public static void main(String[] args) throws Exception {
        int keySpace = 50_000;
        int ops = 20_000;

        runWorkloadA(keySpace, ops);
        runWorkloadB(keySpace, ops);
        runWorkloadC(keySpace, ops);
        runWorkloadD(keySpace, ops);
        runWorkloadF(keySpace, ops);
    }

    // ---------- Workload A: update heavy, 50% read / 50% update, Zipfian ----------
    static void runWorkloadA(int keySpace, int ops) throws Exception {
        LSMTree<Integer> tree = freshTree("ycsb-a");
        load(tree, keySpace);
        ZipfianGenerator zipf = new ZipfianGenerator(keySpace, THETA, RNG);

        long[] latencies = new long[ops];
        for (int i = 0; i < ops; i++) {
            int key = (int) zipf.next();
            long t0 = System.nanoTime();
            if (RNG.nextDouble() < 0.5) tree.get(key);
            else tree.put(key, randomValue());
            latencies[i] = System.nanoTime() - t0;
        }
        tree.close();
        report("Workload A (50% read / 50% update, Zipfian)", latencies);
    }

    // ---------- Workload B: read mostly, 95% read / 5% update, Zipfian ----------
    static void runWorkloadB(int keySpace, int ops) throws Exception {
        LSMTree<Integer> tree = freshTree("ycsb-b");
        load(tree, keySpace);
        ZipfianGenerator zipf = new ZipfianGenerator(keySpace, THETA, RNG);

        long[] latencies = new long[ops];
        for (int i = 0; i < ops; i++) {
            int key = (int) zipf.next();
            long t0 = System.nanoTime();
            if (RNG.nextDouble() < 0.95) tree.get(key);
            else tree.put(key, randomValue());
            latencies[i] = System.nanoTime() - t0;
        }
        tree.close();
        report("Workload B (95% read / 5% update, Zipfian)", latencies);
    }

    // ---------- Workload C: read only, 100% read, Zipfian ----------
    static void runWorkloadC(int keySpace, int ops) throws Exception {
        LSMTree<Integer> tree = freshTree("ycsb-c");
        load(tree, keySpace);
        ZipfianGenerator zipf = new ZipfianGenerator(keySpace, THETA, RNG);

        long[] latencies = new long[ops];
        for (int i = 0; i < ops; i++) {
            int key = (int) zipf.next();
            long t0 = System.nanoTime();
            tree.get(key);
            latencies[i] = System.nanoTime() - t0;
        }
        tree.close();
        report("Workload C (100% read, Zipfian)", latencies);
    }

    // ---------- Workload D: read latest, 95% read (favoring recently inserted) / 5% insert ----------
    static void runWorkloadD(int keySpace, int ops) throws Exception {
        LSMTree<Integer> tree = freshTree("ycsb-d");
        load(tree, keySpace);
        // offset-from-the-end is itself Zipfian: offset 0 (most recent) is by far the most likely
        ZipfianGenerator offsetGen = new ZipfianGenerator(keySpace, THETA, RNG);
        int nextKey = keySpace;

        long[] latencies = new long[ops];
        for (int i = 0; i < ops; i++) {
            long t0 = System.nanoTime();
            if (RNG.nextDouble() < 0.95) {
                int maxKey = nextKey - 1;
                int offset = (int) Math.min(offsetGen.next(), maxKey);
                tree.get(maxKey - offset);
            } else {
                tree.put(nextKey, randomValue());
                nextKey++;
            }
            latencies[i] = System.nanoTime() - t0;
        }
        tree.close();
        report("Workload D (95% read-latest / 5% insert)", latencies);
    }

    // ---------- Workload F: read-modify-write, 50% read / 50% read-then-write, Zipfian ----------
    static void runWorkloadF(int keySpace, int ops) throws Exception {
        LSMTree<Integer> tree = freshTree("ycsb-f");
        load(tree, keySpace);
        ZipfianGenerator zipf = new ZipfianGenerator(keySpace, THETA, RNG);

        long[] latencies = new long[ops];
        for (int i = 0; i < ops; i++) {
            int key = (int) zipf.next();
            long t0 = System.nanoTime();
            if (RNG.nextDouble() < 0.5) {
                tree.get(key);
            } else {
                tree.get(key);
                tree.put(key, randomValue());
            }
            latencies[i] = System.nanoTime() - t0;
        }
        tree.close();
        report("Workload F (50% read / 50% read-modify-write, Zipfian)", latencies);
    }

    // ---------- helpers ----------
    // compactionThreshold=10 keeps segment count bounded during a run, so results are a stable
    // reference point for future comparisons rather than degrading non-stationarily within the
    // run itself (an uncompacted run's later operations face a worse tree than its earlier ones).
    static final int COMPACTION_THRESHOLD = 10;

    static LSMTree<Integer> freshTree(String name) throws Exception {
        Path dir = Files.createTempDirectory("bench-" + name);
        return new LSMTree<>(new IntegerKeyCodec(), dir, 32, 8192,
                new FullCompactStrategy<>(COMPACTION_THRESHOLD), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));
    }

    static void load(LSMTree<Integer> tree, int keySpace) throws Exception {
        for (int i = 0; i < keySpace; i++) tree.put(i, randomValue());
        tree.flush();
    }

    static Value randomValue() {
        byte[] b = new byte[VALUE_SIZE];
        RNG.nextBytes(b);
        return Value.of(b);
    }

    static void report(String label, long[] nanosArr) {
        long[] sorted = nanosArr.clone();
        Arrays.sort(sorted);
        double avgUs = Arrays.stream(sorted).average().orElse(0) / 1000.0;
        double p50Us = sorted[sorted.length / 2] / 1000.0;
        double p99Us = sorted[(int) (sorted.length * 0.99)] / 1000.0;
        double maxUs = sorted[sorted.length - 1] / 1000.0;
        double opsPerSec = nanosArr.length / (Arrays.stream(nanosArr).sum() / 1e9);
        System.out.println(label);
        System.out.printf("  ops/sec=%.0f  avg=%.1fus  p50=%.1fus  p99=%.1fus  max=%.1fus  (n=%d)%n%n",
                opsPerSec, avgUs, p50Us, p99Us, maxUs, nanosArr.length);
    }

    /**
     * YCSB's standard Zipfian generator (Gray et al. inverse-CDF construction, same algorithm
     * YCSB's own ZipfianGenerator uses). theta is the skew parameter -- YCSB's default is 0.99;
     * higher = more skewed toward low ranks.
     */
    static class ZipfianGenerator {
        private final long n;
        private final double theta;
        private final double alpha;
        private final double zetan;
        private final double eta;
        private final Random rng;

        ZipfianGenerator(long n, double theta, Random rng) {
            this.n = n;
            this.theta = theta;
            this.rng = rng;
            this.alpha = 1.0 / (1.0 - theta);
            this.zetan = zeta(n, theta);
            double zeta2 = zeta(2, theta);
            this.eta = (1 - Math.pow(2.0 / n, 1 - theta)) / (1 - zeta2 / zetan);
        }

        private static double zeta(long n, double theta) {
            double sum = 0;
            for (long i = 1; i <= n; i++) sum += 1.0 / Math.pow(i, theta);
            return sum;
        }

        long next() {
            double u = rng.nextDouble();
            double uz = u * zetan;
            if (uz < 1.0) return 0;
            if (uz < 1.0 + Math.pow(0.5, theta)) return 1;
            long value = (long) (n * Math.pow(eta * u - eta + 1, alpha));
            return Math.max(0, Math.min(value, n - 1));
        }
    }
}
