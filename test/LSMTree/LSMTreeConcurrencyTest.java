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
}
