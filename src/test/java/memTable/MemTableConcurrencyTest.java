package memTable;

import core.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the size-accounting races found while wiring MemTable's put() around
 * RBT's AtomicReference-backed insert(): (1) same-key concurrent writers racing on a size delta
 * computed from a stale get()-before-insert read, which overcounted sizeInBytes even though only
 * one value ever survives in the tree, and (2) the plain "sizeInBytes += delta" read-modify-write
 * itself being non-atomic across writers on different keys. Both are fixed by RBT.insert()
 * atomically returning the value it actually replaced, plus sizeInBytes becoming an AtomicInteger.
 */
public class MemTableConcurrencyTest {

    @Test
    @DisplayName("Concurrent writers to the SAME key never overcount size -- final size matches whichever value actually survived")
    void concurrentWritesToSameKeyNeverOvercountSize() throws InterruptedException {
        MemTable<Integer> table = new MemTable<>(Integer.MAX_VALUE);
        int threadCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];

        // Every thread races to write the SAME key, each with a value of a different, known
        // size. Whichever value ends up actually stored is the only one that should count --
        // an overcounting bug would sum deltas from all threads regardless of which one won.
        for (int t = 0; t < threadCount; t++) {
            int size = 10 * (t + 1);
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                    table.put(1, Value.of(new byte[size]));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        for (Thread th : threads) th.start();
        start.countDown();
        for (Thread th : threads) th.join();

        Value finalValue = table.get(1);
        assertEquals(finalValue.getSizeInBytes(), table.getSizeInBytes(),
                "sizeInBytes must equal exactly the surviving value's own size, not a sum across every racing writer");
    }

    @Test
    @DisplayName("Concurrent writers to DISTINCT keys sum their sizes exactly, with no lost updates")
    void concurrentWritesToDistinctKeysSizeSumsCorrectly() throws InterruptedException {
        MemTable<Integer> table = new MemTable<>(Integer.MAX_VALUE);
        int threadCount = 32;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];
        int[] valueSizes = new int[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int key = t;
            int size = 5 * (t + 1);
            valueSizes[t] = size;
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                    table.put(key, Value.of(new byte[size]));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        for (Thread th : threads) th.start();
        start.countDown();
        for (Thread th : threads) th.join();

        int expectedTotal = 0;
        for (int t = 0; t < threadCount; t++) {
            Value stored = table.get(t);
            assertNotNull(stored, "key " + t + " should have been written by its thread");
            expectedTotal += stored.getSizeInBytes();
        }

        assertEquals(expectedTotal, table.getSizeInBytes(),
                "sizeInBytes must equal the exact sum of every distinct key's stored value size -- "
                        + "a lost update on the counter itself would undercount this");
    }

    @Test
    @DisplayName("A single put() of a brand-new key never throws, and size reflects exactly that value")
    void freshKeyInsertDoesNotThrowAndSizeMatchesExactly() {
        MemTable<Integer> table = new MemTable<>(Integer.MAX_VALUE);
        Value value = Value.of(new byte[10]);

        assertDoesNotThrow(() -> table.put(1, value));
        assertEquals(value.getSizeInBytes(), table.getSizeInBytes(),
                "a fresh memTable's size after one insert should equal exactly that value's size");
    }

    @Test
    @DisplayName("Repeated concurrent stress: interleaved same-key and distinct-key writers never drift or throw")
    void mixedConcurrentStressNeverDriftsOrThrows() throws InterruptedException {
        MemTable<Integer> table = new MemTable<>(Integer.MAX_VALUE);
        int keySpace = 20;
        int threadCount = 16;
        int putsPerThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(() -> {
                try {
                    java.util.Random rng = new java.util.Random(threadIndex);
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        int key = rng.nextInt(keySpace);
                        int size = 1 + rng.nextInt(64);
                        table.put(key, Value.of(new byte[size]));
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }

        for (Thread th : threads) th.start();
        start.countDown();
        for (Thread th : threads) th.join();

        assertTrue(failures.isEmpty(), "no thread should throw during concurrent puts: " + failures);

        int expectedTotal = 0;
        for (int k = 0; k < keySpace; k++) {
            Value stored = table.get(k);
            if (stored != null) expectedTotal += stored.getSizeInBytes();
        }
        assertEquals(expectedTotal, table.getSizeInBytes(),
                "after all writers finish, sizeInBytes must equal the sum of every key's final stored value size");
    }
}
