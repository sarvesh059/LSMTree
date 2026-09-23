package LSMTree;

import RBT.Entry;
import compactation.CompactStrategyImpl.FullCompactStrategy;
import compactation.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import cursor.EntrySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class LSMTreeConcurrencyTest {
    private static final int PUTTER_THREADS = 4;
    private static final int GETTER_THREADS = 3;
    private static final int SCANNER_THREADS = 2;
    private static final int MAINTENANCE_THREADS = 2;
    private static final int KEYS_PER_PUTTER = 100;
    private static final long STRESS_DURATION_MS = 800;

    private int keyBase(int putterIndex) {
        return putterIndex * 1_000_000;
    }

    @Test
    @Timeout(60)
    void concurrentPutGetFlushCompactScanNeverCorruptsAndConvergesCorrectly(@TempDir Path dataDir) throws Exception {
        LSMTree<Integer> tree = new LSMTree<>(new IntegerKeyCodec(), dataDir, 5, 40,
                new FullCompactStrategy<>(4), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);

        List<Thread> threads = new java.util.ArrayList<>();

        for (int p = 0; p < PUTTER_THREADS; p++) {
            int base = keyBase(p);
            threads.add(new Thread(() -> {
                try {
                    long version = 0;
                    while (!stop.get()) {
                        for (int k = 0; k < KEYS_PER_PUTTER; k++) {
                            int key = base + k;
                            if (version % 7 == 0) {
                                tree.put(key, Value.tombstone());
                            } else {
                                tree.put(key, Value.of(("v" + version).getBytes(StandardCharsets.UTF_8)));
                            }
                        }
                        version++;
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }

        int totalKeySpaceHigh = keyBase(PUTTER_THREADS - 1) + KEYS_PER_PUTTER;

        for (int g = 0; g < GETTER_THREADS; g++) {
            threads.add(new Thread(() -> {
                try {
                    while (!stop.get()) {
                        int key = (int) (Math.random() * totalKeySpaceHigh);
                        tree.get(key);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }

        for (int s = 0; s < SCANNER_THREADS; s++) {
            threads.add(new Thread(() -> {
                try {
                    while (!stop.get()) {
                        Integer prevKey = null;
                        try (EntrySource<Integer> cursor = tree.scan(0, totalKeySpaceHigh)) {
                            while (cursor.hasNext()) {
                                Entry<Integer, Value> e = cursor.next();
                                if (prevKey != null && e.getKey() <= prevKey) {
                                    throw new AssertionError("out-of-order scan result: " + prevKey + " then " + e.getKey());
                                }
                                prevKey = e.getKey();
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }

        for (int m = 0; m < MAINTENANCE_THREADS; m++) {
            threads.add(new Thread(() -> {
                try {
                    while (!stop.get()) {
                        tree.flush();
                        tree.compact();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }

        threads.forEach(Thread::start);
        Thread.sleep(STRESS_DURATION_MS);
        stop.set(true);
        for (Thread t : threads) t.join();

        assertNull(failure.get(), () -> "stress phase threw: " + failure.get());

        Map<Integer, Value> expected = new HashMap<>();
        for (int p = 0; p < PUTTER_THREADS; p++) {
            int base = keyBase(p);
            for (int k = 0; k < KEYS_PER_PUTTER; k++) {
                int key = base + k;
                Value finalValue = Value.of(("final-" + key).getBytes(StandardCharsets.UTF_8));
                tree.put(key, finalValue);
                expected.put(key, finalValue);
            }
        }
        tree.flush();

        for (Map.Entry<Integer, Value> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), tree.get(entry.getKey()),
                    "key " + entry.getKey() + " should hold its final deterministic value after the stress phase settled");
        }

        Map<Integer, Value> scanned = new HashMap<>();
        Integer prevKey = null;
        try (EntrySource<Integer> cursor = tree.scan(0, totalKeySpaceHigh)) {
            while (cursor.hasNext()) {
                Entry<Integer, Value> e = cursor.next();
                assertTrue(prevKey == null || e.getKey() > prevKey, "final scan must be strictly ascending");
                prevKey = e.getKey();
                scanned.put(e.getKey(), e.getValue());
            }
        }
        assertEquals(expected, scanned, "final scan should return exactly the expected key/value set, no more, no less");

        assertTrue(tree.segmentCount() > 0, "sanity check: flush/compact genuinely ran during the stress phase");

        tree.close();
    }

    /**
     * Regression test for a real, proven bug: scan() used to read segments and memTable as two
     * separately-captured views, not one atomic snapshot. A key could fall into the gap between an
     * in-flight flush()'s segment-swap and its memTable-clear and vanish from that one scan
     * entirely, even though it existed continuously from the writer's perspective. Fixed by
     * bundling memTable + segments into one Version object, published via a single
     * AtomicReference, swapped atomically as one unit on every flush()/compact(). Unlike the
     * broader stress test above (which only checks final, post-stress correctness and in-flight
     * ordering), this specifically checks completeness of every single in-flight scan -- every key
     * that was ever written must appear in every scan taken during the run, since none of them are
     * ever deleted.
     */
    @Test
    @Timeout(60)
    void scanNeverLosesAKeyToAConcurrentFlush(@TempDir Path dataDir) throws Exception {
        LSMTree<Integer> tree = new LSMTree<>(new IntegerKeyCodec(), dataDir, 32, 200,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));

        int keySpace = 50;
        for (int k = 0; k < keySpace; k++) tree.put(k, Value.of(("v" + k).getBytes(StandardCharsets.UTF_8)));

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                int i = 0;
                while (!stop.get()) {
                    int key = i % keySpace;
                    tree.put(key, Value.of(("updated" + i).getBytes(StandardCharsets.UTF_8)));
                    i++;
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread scanner = new Thread(() -> {
            try {
                for (int round = 0; round < 500 && failure.get() == null; round++) {
                    java.util.Set<Integer> seen = new java.util.HashSet<>();
                    try (EntrySource<Integer> cursor = tree.scan(0, keySpace - 1)) {
                        while (cursor.hasNext()) seen.add(cursor.next().getKey());
                    }
                    if (seen.size() != keySpace) {
                        java.util.Set<Integer> missing = new java.util.HashSet<>();
                        for (int k = 0; k < keySpace; k++) if (!seen.contains(k)) missing.add(k);
                        failure.compareAndSet(null, new AssertionError(
                                "round " + round + ": scan saw " + seen.size() + " of " + keySpace
                                        + " keys -- missing " + missing));
                        return;
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        scanner.start();
        writer.start();
        scanner.join();
        stop.set(true);
        writer.join();

        assertNull(failure.get(), () -> "scan lost a key mid-flush: " + failure.get());

        tree.close();
    }
}
