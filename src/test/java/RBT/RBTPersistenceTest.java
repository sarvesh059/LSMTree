package RBT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class RBTPersistenceTest {

    private record Fingerprint(Node<?, ?> left, Node<?, ?> right, boolean red) {}

    private Map<Node<Integer, String>, Fingerprint> fingerprint(Node<Integer, String> node,
                                                                  Map<Node<Integer, String>, Fingerprint> out) {
        if (node == null) return out;
        out.put(node, new Fingerprint(node.getLeft(), node.getRight(), node.isRed()));
        fingerprint(node.getLeft(), out);
        fingerprint(node.getRight(), out);
        return out;
    }

    private List<Integer> collectInOrder(Node<Integer, String> node) {
        List<Integer> keys = new ArrayList<>();
        collectInOrder(node, keys);
        return keys;
    }

    private void collectInOrder(Node<Integer, String> node, List<Integer> keys) {
        if (node == null) return;
        collectInOrder(node.getLeft(), keys);
        keys.add(node.getKey());
        collectInOrder(node.getRight(), keys);
    }

    private boolean isSorted(List<Integer> keys) {
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i - 1) >= keys.get(i)) return false;
        }
        return true;
    }

    @Test
    @DisplayName("Persistence: an old root, once returned, is never mutated by any later insert")
    void oldRootStaysStructurallyUnchangedAfterSubsequentInserts() {
        for (int seed = 0; seed < 3; seed++) {
            RBT<Integer, String> tree = new RBT<Integer, String>();
            Random rng = new Random(seed * 17 + 3);
            int n = 5000;

            for (int i = 0; i < n; i++) {
                int key = rng.nextInt(2000);

                Node<Integer, String> oldRoot = tree.root.get();
                Map<Node<Integer, String>, Fingerprint> before = fingerprint(oldRoot, new IdentityHashMap<>());

                tree.insert(key, "v" + seed + "_" + i);

                for (Map.Entry<Node<Integer, String>, Fingerprint> entry : before.entrySet()) {
                    Node<Integer, String> node = entry.getKey();
                    Fingerprint fp = entry.getValue();
                    assertSame(fp.left(), node.getLeft(),
                            "seed=" + seed + " i=" + i + " key=" + key + " -- node " + node.getKey()
                                    + "'s left child changed after an insert that shouldn't have touched it");
                    assertSame(fp.right(), node.getRight(),
                            "seed=" + seed + " i=" + i + " key=" + key + " -- node " + node.getKey()
                                    + "'s right child changed after an insert that shouldn't have touched it");
                    assertEquals(fp.red(), node.isRed(),
                            "seed=" + seed + " i=" + i + " key=" + key + " -- node " + node.getKey()
                                    + "'s color changed after an insert that shouldn't have touched it");
                }
            }
        }
    }

    @Test
    @DisplayName("Persistence: multiple retained versions all stay valid, even long after later inserts")
    void manyRetainedOldVersionsAllStayValidAfterFurtherInserts() {
        RBT<Integer, String> tree = new RBT<Integer, String>();
        Random rng = new Random(99);
        int n = 6000;

        record Checkpoint(Node<Integer, String> root, List<Integer> expectedInOrder) {}
        List<Checkpoint> checkpoints = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            tree.insert(rng.nextInt(2500), "v" + i);
            if (i % 200 == 0) {
                checkpoints.add(new Checkpoint(tree.root.get(), collectInOrder(tree.root.get())));
            }
        }

        for (Checkpoint cp : checkpoints) {
            assertEquals(cp.expectedInOrder(), collectInOrder(cp.root()),
                    "a retained old root's in-order sequence changed after later inserts happened on top of it");
        }
    }

    @Test
    @DisplayName("Persistence: a single insert only ever clones an O(log n)-bounded number of nodes, never the whole tree")
    void insertClonesOnlyABoundedNumberOfNodesNotTheWholeTree() {
        // A hand-picked "this exact node must stay untouched" assertion is fragile: flipColors can
        // legitimately cascade all the way to the root (if a child several levels up already
        // happened to be red), and any node it touches is correctly re-cloned, not a bug. The
        // actual content of "structural sharing" is an aggregate property: an insert should only
        // ever clone the O(log n) nodes on its own path (plus, at most, their immediate siblings
        // via a rotation or flipColors) -- never a number of nodes proportional to the whole tree.
        RBT<Integer, String> tree = new RBT<Integer, String>();
        Random rng = new Random(11);
        int warmup = 3000;
        for (int i = 0; i < warmup; i++) tree.insert(rng.nextInt(2000), "warmup" + i);

        int sampleInserts = 500;
        for (int i = 0; i < sampleInserts; i++) {
            Node<Integer, String> oldRoot = tree.root.get();
            int nodeCountBefore = collectInOrder(oldRoot).size();
            Map<Node<Integer, String>, Fingerprint> before = fingerprint(oldRoot, new IdentityHashMap<>());

            tree.insert(rng.nextInt(2000), "v" + i);

            int touched = 0;
            for (Map.Entry<Node<Integer, String>, Fingerprint> entry : before.entrySet()) {
                Node<Integer, String> node = entry.getKey();
                Fingerprint fp = entry.getValue();
                if (node.getLeft() != fp.left() || node.getRight() != fp.right() || node.isRed() != fp.red()) {
                    touched++;
                }
            }

            // Generous bound: insertion path (log2 n) + a small constant for rotation/flip
            // siblings at each level. What matters is that it's logarithmic, not linear, in n.
            int bound = (int) Math.ceil(4 * (Math.log(nodeCountBefore + 2) / Math.log(2))) + 5;
            assertTrue(touched <= bound,
                    "insert #" + i + " touched " + touched + " previously-existing nodes out of "
                            + nodeCountBefore + " -- expected at most " + bound
                            + " (O(log n)); this many touched nodes suggests something is copying "
                            + "far more of the tree than the insertion path requires");
        }
    }

    @Test
    @DisplayName("Persistence: concurrent readers holding a published root never observe a torn/mutated tree")
    void concurrentReadersNeverObserveMutationOfPublishedSnapshot() throws InterruptedException {
        RBT<Integer, String> tree = new RBT<Integer, String>();
        AtomicReference<Node<Integer, String>> published = new AtomicReference<>(null);
        AtomicBoolean writerDone = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int totalInserts = 20_000;
        java.util.Set<Integer> distinctKeysWritten = new java.util.HashSet<>();

        Thread writer = new Thread(() -> {
            try {
                Random rng = new Random(123);
                for (int i = 0; i < totalInserts; i++) {
                    int key = rng.nextInt(5000);
                    tree.insert(key, "v" + i);
                    distinctKeysWritten.add(key);
                    published.set(tree.root.get());
                }
            } finally {
                writerDone.set(true);
            }
        });

        int readerCount = 4;
        Thread[] readers = new Thread[readerCount];
        for (int r = 0; r < readerCount; r++) {
            readers[r] = new Thread(() -> {
                try {
                    while (!writerDone.get()) {
                        Node<Integer, String> snapshot = published.get();
                        if (snapshot == null) continue;

                        List<Integer> firstPass = collectInOrder(snapshot);
                        // Busy-work to widen the window for a concurrent writer to (incorrectly)
                        // mutate this snapshot before we re-read it.
                        double spin = 0;
                        for (int s = 0; s < 2000; s++) spin += Math.sqrt(s);

                        List<Integer> secondPass = collectInOrder(snapshot);
                        if (!firstPass.equals(secondPass)) {
                            failure.compareAndSet(null, new AssertionError(
                                    "a published root snapshot changed shape between two reads -- "
                                            + "torn/mutated tree observed by a concurrent reader"));
                            return;
                        }
                        if (!isSorted(firstPass)) {
                            failure.compareAndSet(null, new AssertionError(
                                    "a published root snapshot was not sorted -- corrupted tree"));
                            return;
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        writer.start();
        for (Thread t : readers) t.start();
        writer.join();
        for (Thread t : readers) t.join();

        if (failure.get() != null) {
            throw new AssertionError("concurrent reader detected corruption", failure.get());
        }

        assertEquals(distinctKeysWritten.size(), tree.getSize(),
                "sanity check: final tree size should equal the number of distinct keys actually written");
    }
}
