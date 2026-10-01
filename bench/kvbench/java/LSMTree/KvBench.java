package LSMTree;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Java side of the benchmark kit. Runs the fixed workload contract against LSMTree through
 * {@link CandidateAdapter}. Do NOT change anything in this file that affects the workload
 * (key/value generation, RNG, counts, phase order, verification). Adapt CandidateAdapter instead.
 *
 * It lives in package LSMTree only so the adapter can reach package-private methods; once the
 * engine has a proper public API, it can move anywhere.
 */
public final class KvBench {

    /** What the harness needs from an engine. Implemented by CandidateAdapter. */
    public interface Kv extends AutoCloseable {
        /** sync=true: durable when this returns. sync=false: may throw UnsupportedOperationException. */
        void put(String key, byte[] value, boolean sync) throws IOException;
        /** Returns the value, or null if absent or deleted. */
        byte[] get(String key) throws IOException;
        /** Visits live keys in [low, high] ascending, at most limit of them; returns how many were visited. */
        int scan(String low, String high, int limit, Visitor visitor) throws IOException;
        /** Memtable flushed, all background work finished, data fully compacted. */
        void settle() throws IOException;
        /** One line describing the engine configuration; printed with the results. */
        String describe();
        @Override void close() throws IOException;
    }

    public interface Visitor { boolean visit(String key, byte[] value); }

    // ------------------------------------------------------------------ workload primitives (must match kvbench.cc)
    static final class SplitMix64 {
        private long s;
        SplitMix64(long seed) { s = seed; }
        long next() {
            long z = (s += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
        long below(long n) { return Long.remainderUnsigned(next(), n); }
    }

    static String key(long i) {
        char[] k = "user000000000000".toCharArray();
        for (int p = 15; p >= 4 && i > 0; --p) { k[p] = (char) ('0' + i % 10); i /= 10; }
        return new String(k);
    }
    static String missKey(long i) { return key(i) + "x"; }
    static byte[] value(long i) {
        byte[] v = new byte[100];
        System.arraycopy(key(i).getBytes(StandardCharsets.US_ASCII), 0, v, 0, 16);
        for (int j = 16; j < 100; j++) v[j] = (byte) ((i * 131 + j * 17) & 0xFF);
        return v;
    }

    // ------------------------------------------------------------------ harness
    static final String ALL_PHASES = "fill_nosync,fill_sync,settle,read_hit,read_miss,scan100,fill_sync_mt";

    static final class Args {
        String db; Set<String> phases = new HashSet<>();
        long keys = 100_000, hits = 100_000, misses = 100_000, scans = 10_000, warmup = 20_000, perWriter = 5_000, seed = 42;
        int scanLen = 100, writers = 8;
        String profilePhase, profileEvent = "cpu", profileOut = "flame.html";
        boolean want(String p) { return phases.contains(p); }
    }

    static Args parse(String[] argv) {
        Args a = new Args();
        String phases = ALL_PHASES;
        for (int i = 0; i < argv.length; i++) {
            String f = argv[i];
            if (i + 1 >= argv.length) usage("missing value for " + f);
            String v = argv[++i];
            switch (f) {
                case "--db" -> a.db = v;
                case "--phases" -> phases = v;
                case "--keys" -> a.keys = Long.parseLong(v);
                case "--hits" -> a.hits = Long.parseLong(v);
                case "--misses" -> a.misses = Long.parseLong(v);
                case "--scans" -> a.scans = Long.parseLong(v);
                case "--warmup" -> a.warmup = Long.parseLong(v);
                case "--writers" -> a.writers = Integer.parseInt(v);
                case "--per-writer" -> a.perWriter = Long.parseLong(v);
                case "--seed" -> a.seed = Long.parseLong(v);
                case "--profile-phase" -> a.profilePhase = v;
                case "--profile-event" -> a.profileEvent = v;
                case "--profile-out" -> a.profileOut = v;
                case "--engine" -> { /* accepted for symmetry with kvbench.cc; always "candidate" here */ }
                default -> usage("unknown flag " + f);
            }
        }
        if (a.db == null) usage("--db is required");
        for (String p : phases.split(",")) if (!p.isBlank()) a.phases.add(p.trim());
        return a;
    }

    static void usage(String msg) {
        System.err.println(msg + "\nusage: KvBench --db DIR [--phases " + ALL_PHASES + "] [--keys N] ...");
        System.exit(64);
    }

    static final String E = "candidate";
    static Args ARGS;

    /**
     * Optional: attach async-profiler (asprof) to this JVM for exactly one phase's timed loop.
     * Set ASPROF to the asprof binary. Timings from a profiled run are perturbed: never report them.
     */
    static void profile(String action, String phase) throws IOException, InterruptedException {
        if (ARGS.profilePhase == null || !ARGS.profilePhase.equals(phase)) return;
        String asprof = System.getenv().getOrDefault("ASPROF", "asprof");
        String pid = String.valueOf(ProcessHandle.current().pid());
        java.util.List<String> cmd = action.equals("start")
                ? java.util.List.of(asprof, "start", "-e", ARGS.profileEvent, "-t", pid)
                : java.util.List.of(asprof, "stop", "-f", ARGS.profileOut, pid);
        int rc = new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (rc != 0) throw new IOException("asprof " + action + " failed with exit code " + rc);
        if (action.equals("start")) System.out.println("INFO profiling phase=" + phase + " event=" + ARGS.profileEvent + " (timings in this run are perturbed; don't report them)");
    }

    static void report(String phase, long[] ns, double seconds) {
        Arrays.sort(ns);
        int n = ns.length;
        if (n == 0) return;
        System.out.printf("RESULT engine=%s phase=%s ops=%d ops_per_sec=%.0f p50_us=%.2f p99_us=%.2f max_us=%.1f%n",
                E, phase, n, n / seconds, ns[n / 2] / 1e3, ns[Math.min(n - 1, (int) (n * 0.99))] / 1e3, ns[n - 1] / 1e3);
        System.out.flush();
    }

    static void verifyFailed(String phase, String detail) {
        System.out.println("VERIFY_FAILED engine=" + E + " phase=" + phase + " detail=" + detail);
        System.out.flush();
        System.exit(2);
    }

    static void rmrf(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.delete(f); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }

    static long dirBytes(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    static Kv openFresh(Path dir) throws IOException {
        rmrf(dir);
        Files.createDirectories(dir.getParent());
        return CandidateAdapter.open(dir);
    }

    public static void main(String[] argv) throws Exception {
        Args a = parse(argv);
        ARGS = a;
        Path base = Paths.get(a.db);

        SplitMix64 rng = new SplitMix64(a.seed);
        int N = Math.toIntExact(a.keys);
        long[] order = new long[N];
        for (int i = 0; i < N; i++) order[i] = i;
        for (int i = N - 1; i > 0; i--) { int j = (int) rng.below(i + 1); long t = order[i]; order[i] = order[j]; order[j] = t; }
        String[] keys = new String[N];
        byte[][] vals = new byte[N][];
        for (int i = 0; i < N; i++) { keys[i] = key(i); vals[i] = value(i); }

        if (a.want("fill_nosync")) {
            try (Kv kv = openFresh(base.resolve(E + "-nosync"))) {
                long[] ns = new long[N];
                long t0 = System.nanoTime();
                try {
                    for (int k = 0; k < N; k++) { int i = (int) order[k]; long s = System.nanoTime(); kv.put(keys[i], vals[i], false); ns[k] = System.nanoTime() - s; }
                    report("fill_nosync", ns, (System.nanoTime() - t0) / 1e9);
                } catch (UnsupportedOperationException e) {
                    System.out.println("RESULT engine=" + E + " phase=fill_nosync unsupported");
                }
            }
        }

        boolean needLoad = a.want("fill_sync") || a.want("settle") || a.want("read_hit") || a.want("read_miss") || a.want("scan100");
        if (needLoad) {
            Path path = base.resolve(E);
            try (Kv kv = openFresh(path)) {
                System.out.println("INFO engine=" + E + " config=\"" + kv.describe() + "\"");
                long[] ns = new long[N];
                profile("start", "fill_sync");
                long t0 = System.nanoTime();
                for (int k = 0; k < N; k++) { int i = (int) order[k]; long s = System.nanoTime(); kv.put(keys[i], vals[i], true); ns[k] = System.nanoTime() - s; }
                double fillSecs = (System.nanoTime() - t0) / 1e9;
                profile("stop", "fill_sync");
                report("fill_sync", ns, fillSecs);

                long s0 = System.nanoTime();
                kv.settle();
                System.out.printf("RESULT engine=%s phase=settle seconds=%.3f disk_mb=%.2f logical_mb=%.2f%n", E,
                        (System.nanoTime() - s0) / 1e9, dirBytes(path) / 1048576.0, a.keys * 116 / 1048576.0);

                int H = Math.toIntExact(a.hits), M = Math.toIntExact(a.misses), S = Math.toIntExact(a.scans), W = Math.toIntExact(a.warmup);
                int[] warm = new int[W], hitIdx = new int[H], missIdx = new int[M], scanIdx = new int[S];
                for (int i = 0; i < W; i++) warm[i] = (int) rng.below(a.keys);
                for (int i = 0; i < H; i++) hitIdx[i] = (int) rng.below(a.keys);
                for (int i = 0; i < M; i++) missIdx[i] = (int) rng.below(a.keys);
                for (int i = 0; i < S; i++) scanIdx[i] = (int) rng.below(a.keys - a.scanLen);
                String[] missKeys = new String[M];
                for (int i = 0; i < M; i++) missKeys[i] = missKey(missIdx[i]);

                if (a.want("read_hit")) {
                    for (int i : warm) kv.get(keys[i]);
                    ns = new long[H];
                    profile("start", "read_hit");
                    long t = System.nanoTime();
                    for (int k = 0; k < H; k++) {
                        int i = hitIdx[k];
                        long s = System.nanoTime();
                        byte[] out = kv.get(keys[i]);
                        ns[k] = System.nanoTime() - s;
                        if (out == null || !Arrays.equals(out, vals[i])) verifyFailed("read_hit", "wrong or missing value for " + keys[i]);
                    }
                    double secs = (System.nanoTime() - t) / 1e9;
                    profile("stop", "read_hit");
                    report("read_hit", ns, secs);
                }
                if (a.want("read_miss")) {
                    for (int i = 0; i < Math.min(W, M); i++) kv.get(missKeys[i]);
                    ns = new long[M];
                    profile("start", "read_miss");
                    long t = System.nanoTime();
                    for (int k = 0; k < M; k++) {
                        long s = System.nanoTime();
                        byte[] out = kv.get(missKeys[k]);
                        ns[k] = System.nanoTime() - s;
                        if (out != null) verifyFailed("read_miss", "found a key that was never written: " + missKeys[k]);
                    }
                    double secs = (System.nanoTime() - t) / 1e9;
                    profile("stop", "read_miss");
                    report("read_miss", ns, secs);
                }
                if (a.want("scan100")) {
                    for (int w = 0; w < W / 20; w++) { int i = scanIdx[w % S]; kv.scan(keys[i], keys[i + a.scanLen - 1], a.scanLen, new Check(keys[i])); }
                    ns = new long[S];
                    profile("start", "scan100");
                    long t = System.nanoTime();
                    for (int k = 0; k < S; k++) {
                        int i = scanIdx[k];
                        Check c = new Check(keys[i]);
                        long s = System.nanoTime();
                        int n = kv.scan(keys[i], keys[i + a.scanLen - 1], a.scanLen, c);
                        ns[k] = System.nanoTime() - s;
                        if (c.bad || n != a.scanLen) verifyFailed("scan100", "scan from " + keys[i] + " returned " + n + " valid rows");
                    }
                    double secs = (System.nanoTime() - t) / 1e9;
                    profile("stop", "scan100");
                    report("scan100", ns, secs);
                }
            }
        }

        if (a.want("fill_sync_mt")) {
            try (Kv kv = openFresh(base.resolve(E + "-mt"))) {
                int T = a.writers, P = Math.toIntExact(a.perWriter);
                String[][] wk = new String[T][P];
                byte[][][] wv = new byte[T][P][];
                for (int t = 0; t < T; t++)
                    for (int i = 0; i < P; i++) { long id = 1_000_000_000L + (long) t * 1_000_000 + i; wk[t][i] = key(id); wv[t][i] = value(id); }
                CountDownLatch start = new CountDownLatch(1);
                Thread[] ts = new Thread[T];
                Throwable[] failure = new Throwable[1];
                for (int t = 0; t < T; t++) {
                    int id = t;
                    ts[t] = new Thread(() -> {
                        try { start.await(); for (int i = 0; i < P; i++) kv.put(wk[id][i], wv[id][i], true); }
                        catch (Throwable e) { synchronized (failure) { failure[0] = e; } }
                    });
                    ts[t].start();
                }
                profile("start", "fill_sync_mt");
                long t0 = System.nanoTime();
                start.countDown();
                for (Thread th : ts) th.join();
                double secs = (System.nanoTime() - t0) / 1e9;
                profile("stop", "fill_sync_mt");
                if (failure[0] != null) verifyFailed("fill_sync_mt", "writer threw " + failure[0]);
                for (int t = 0; t < T; t++)
                    for (int i = 0; i < P; i++) {
                        byte[] out = kv.get(wk[t][i]);
                        if (out == null || !Arrays.equals(out, wv[t][i])) verifyFailed("fill_sync_mt", "lost write " + wk[t][i]);
                    }
                System.out.printf("RESULT engine=%s phase=fill_sync_mt ops=%d ops_per_sec=%.0f writers=%d%n", E, (long) T * P, T * P / secs, T);
            }
        }
        System.exit(0); // don't wait on non-daemon background threads the engine may leave running
    }

    /** Scan verifier: first key == low, strictly ascending, 100-byte values that start with their own key. */
    static final class Check implements Visitor {
        final String low; String last; int seen; boolean bad;
        Check(String low) { this.low = low; }
        @Override public boolean visit(String k, byte[] v) {
            if ((seen == 0 && !k.equals(low)) || (seen > 0 && k.compareTo(last) <= 0) || v == null || v.length != 100 || k.length() < 16) {
                bad = true; return false;
            }
            for (int j = 0; j < 16; j++) if (v[j] != (byte) k.charAt(j)) { bad = true; return false; }
            last = k; seen++; return true;
        }
    }
}
