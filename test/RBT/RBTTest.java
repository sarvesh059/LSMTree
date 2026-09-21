package RBT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RBTTest {

    RBT<Integer, String> tree;

    private Node<Integer, String> buildRotationFixture(){
        Node<Integer, String> a = new Node<>(1, "a");
        Node<Integer, String> q = new Node<>(2, "q");
        Node<Integer, String> b = new Node<>(3, "b");
        Node<Integer, String> x = new Node<>(4, "x");
        Node<Integer, String> c = new Node<>(5, "c");

        q.setLeft(a);
        q.setRight(x);
        x.setLeft(b);
        x.setRight(c);

        return q;
    }

    private List<Integer> collectInOrder(Node<Integer,String> node){
        List<Integer> keys = new ArrayList<>();
        collectInOrder(node, keys);
        return keys;
    }
    private void collectInOrder(Node<Integer,String> node, List<Integer> keys){
        if(node == null) return;
        collectInOrder(node.getLeft(), keys);
        keys.add(node.getKey());
        collectInOrder(node.getRight(), keys);
    }

    @BeforeEach
    void setUp(){
        tree = new RBT<Integer, String>();
    }

    @Test
    void insertingKeysProducesAscendingInOrder(){
        int[] keys = {5, 2, 8, 1, 9, 3};
        for(int k: keys) tree.insert(k, "v"+k);

        List<Integer> inOrder = tree.inOrderKeys();
        assertEquals(keys.length, inOrder.size(), "keys should be equal in size");
        for(int i=1; i<inOrder.size(); i++){
            assertTrue(inOrder.get(i-1) < inOrder.get(i), "expected strictly ascending, got "+inOrder);
        }
    }

    @Test
    void getReturnsInsertedValue(){
        tree.insert(5, "five");
        assertEquals("five", tree.get(5));
    }

    @Test
    void getOnAbsentKeyReturnsNull(){
        tree.insert(5, "five");
        assertNull(tree.get(6));
    }

    @Test
    void duplicateKeyOverwritesValueWithoutNewNode(){
        tree.insert(1, "a");
        tree.insert(1, "b");

        assertEquals("b", tree.get(1));
        assertEquals(1, tree.getSize());
    }

    @Test
    void sizeCountsDistinctKeysAcrossInsertsAndUpdated(){
        tree.insert(1, "a");
        tree.insert(2, "b");
        tree.insert(1, "updated");

        assertEquals(2, tree.getSize());
    }

    @Test
    @DisplayName("T1.2: rotateLeft then matching rotateRight restores the identical tree structure")
    void rotateLeftThenRotateRightRestoresOriginalTree(){
        //Arrange
        Node<Integer, String> q = buildRotationFixture();

        //Act
        Node<Integer, String> afterLeft = tree.rotateLeft(q);
        Node<Integer, String> restored = tree.rotateRight(afterLeft);

        //Assert
        // rotateLeft/rotateRight are persistent: they clone the node being rotated into instead
        // of mutating it in place (see RBT persistence work), so a round trip does NOT return the
        // original node objects -- it returns fresh clones with the identical shape/keys/values/
        // colors. Compare by value, not by reference. (Reference-sharing of untouched subtrees is
        // covered separately by the persistence tests.)
        Node<Integer, String> expected = buildRotationFixture();
        assertTrue(structurallyEqual(expected, restored),
                "round trip must reconstruct a tree with the identical shape, keys, values and colors");
    }

    private boolean structurallyEqual(Node<Integer, String> a, Node<Integer, String> b){
        if(a == null || b == null) return a == b;
        if(!Objects.equals(a.getKey(), b.getKey())) return false;
        if(!Objects.equals(a.getVal(), b.getVal())) return false;
        if(a.isRed() != b.isRed()) return false;
        return structurallyEqual(a.getLeft(), b.getLeft()) && structurallyEqual(a.getRight(), b.getRight());
    }

    @Test
    @DisplayName("TI.2: any single rotation preserves the inorder key sequence")
    void rotationPreservesInOrderSequence(){
        Node<Integer, String> q = buildRotationFixture();
        List<Integer> before = collectInOrder(q);

        Node<Integer, String> afterLeft = tree.rotateLeft(q);
        assertEquals(before, collectInOrder(afterLeft), "left rotation must not change key order");

        Node<Integer, String> afterRight = tree.rotateRight(afterLeft);
        assertEquals(before, collectInOrder(afterRight), "right rotation must not change key order");
    }

    @Test
    @DisplayName("T1.3: 10k random inserts maintain all red-black invariance")
    void randomInsertsMaintainedRedBlackInvariants(){
        //Arrange
        int n = 10_000;
        List<Integer> keys = new ArrayList<>();
        for(int i=0;i<n;i++) keys.add(i);
        Collections.shuffle(keys, new Random(42));

        //Act
        for(int k : keys) tree.insert(k, "v" + k);

        //Assert
        Node<Integer, String> root = tree.root;
        List<Integer> inOrderKeys = tree.inOrderKeys();
        int measuredHeight = height(root);
        double heightBound = 2 * (Math.log(n+1)/Math.log(2));

        assertAll("red-black invariants after "+ n + " random inserts",
                () -> assertFalse(root.isRed(), "(a) root must be black"),
                () -> assertTrue(noRedViolation(root), "(b) no red node may have a red child"),
                () -> assertTrue(blackHeight(root) != -1, "(c) black-height must be equal on every root->NIL path"),
                () -> assertTrue(isSorted(inOrderKeys), "(d) in-order traversal must be strictly ascending"),
                () -> assertTrue(measuredHeight <= heightBound, "(e) height "+ measuredHeight + "exceeds bound "+ heightBound)
        );
    }

    private boolean isSorted(List<Integer> keys){
        for(int i=1;i< keys.size(); i++){
            if(keys.get(i-1) >= keys.get(i)) return false;
        }
        return true;
    }

    private boolean noRedViolation(Node<Integer, String> node){
        if(node == null) return true;
        if(node.isRed() && (node.isLeftChildRed() || node.isRightChildRed())) return false;
        return noRedViolation(node.getLeft()) && noRedViolation(node.getRight());
    }

    private int blackHeight(Node<Integer, String> node){
        if(node == null) return 1;
        int left = blackHeight(node.getLeft());
        int right = blackHeight(node.getRight());
        if(right == -1 || left != right) return -1;
        return left + (node.isRed() ? 0 : 1);
    }

    private int height(Node<Integer, String> node){
        if(node == null) return 0;
        return 1+ Math.max(height(node.getLeft()), height(node.getRight()));
    }

    private void insertKeys(int... keys){
        for(int k : keys) tree.insert(k, "v"+ k);
    }

    @Test
    void rangeReturnsKeysWithinInclusiveBounds(){
        insertKeys(10, 20, 30, 40 , 50);
        assertEquals(List.of(20, 30, 40), tree.range(20, 40));
    }

    @Test
    void rangeWithBoundsBetweenExistingKeys(){
        insertKeys(10, 30, 20, 50, 40);
        assertEquals(List.of(20,30,40), tree.range(15, 45));
    }

    @Test
    void rangeCoveringEntireTreeIncludesBoundaries(){
        insertKeys(10, 30, 20, 50, 40);
        assertEquals(List.of(10, 20,30,40, 50), tree.range(10, 50));
    }

    @Test
    void rangeOnSingleExactKey(){
        insertKeys(10, 30, 20, 50, 40);
        assertEquals(List.of(30), tree.range(30, 30));
    }

    @Test
    void rangeOutsideTreeBoundaries(){
        insertKeys(10, 30, 20, 50, 40);
        assertAll(
                () -> assertEquals(List.of(), tree.range(0,5)),
                () -> assertEquals(List.of(), tree.range(60, 100))
        );
    }

    @Test
    void rangeWithEmptyWindowBetweenKeysIsEmpty(){
        insertKeys(10, 20, 30, 40 ,50);
        assertEquals(List.of(), tree.range(21, 29));
    }

    @ParameterizedTest(name = "floor({0}) = {1}")
    @CsvSource(value = {
            "30, 30",  // exact match
            "35, 30",  // between keys
            "5, null", // below every key
            "100, 50"  // above every key
    }, nullValues = "null")
    void floorReturnsLargestKeyLessOrEqual(int query, Integer expected){
        insertKeys(10, 20, 30, 40 ,50);
        assertEquals(expected, tree.floor(query));
    }

    @ParameterizedTest(name = "ceiling({0}) = {1}")
    @CsvSource(value = {
            "30, 30",  // exact match
            "35, 40",  // between keys
            "5, 10", // below every key
            "100, null"  // above every key
    }, nullValues = "null")
    void ceilingReturnsSmallestKeyGreaterOrEqual(int query, Integer expected){
        insertKeys(10, 20, 30, 40 ,50);
        assertEquals(expected, tree.ceiling(query));
    }

    @Test
    void emptyTreeBoundaryOperations(){
        assertAll(
                () -> assertNull(tree.floor(1)),
                () -> assertNull(tree.ceiling(1)),
                () -> assertEquals(List.of(), tree.range(0, 100))
        );
    }

    @Test
    @DisplayName("T1.6: deleting a leaf removes it and decrements size")
    void deleteLeafRemovesKey(){
        insertKeys(20, 10, 30);
        tree.delete(10);

        assertAll(
                () -> assertNull(tree.get(10)),
                () -> assertEquals("v20", tree.get(20)),
                () -> assertEquals("v30", tree.get(30)),
                () -> assertEquals(2, tree.getSize())
        );
    }

    @Test
    @DisplayName("T1.6: deleting a node with two children preserves the rest of the tree")
    void deleteNodeWithTwoChildren(){
        insertKeys(20, 10, 30, 5, 15, 25, 35);
        tree.delete(20);

        assertAll(
                () -> assertNull(tree.get(20)),
                () -> assertEquals(List.of(5, 10, 15, 25, 30, 35), tree.inOrderKeys()),
                () -> assertEquals(6, tree.getSize())
        );
    }

    @Test
    @DisplayName("T1.6: deleting the only key empties the tree")
    void deleteLastKeyEmptiesTree(){
        insertKeys(42);
        tree.delete(42);

        assertAll(
                () -> assertNull(tree.get(42)),
                () -> assertEquals(0, tree.getSize()),
                () -> assertEquals(List.of(), tree.inOrderKeys())
        );
    }

    @Test
    @DisplayName("T1.6: deleting a key that was never inserted is a no-op")
    void deleteAbsentKeyIsNoOp(){
        insertKeys(10, 20, 30);
        tree.delete(99);

        assertAll(
                () -> assertEquals(3, tree.getSize()),
                () -> assertEquals(List.of(10, 20, 30), tree.inOrderKeys())
        );
    }

    @Test
    @DisplayName("T1.6: delete on an empty tree does not throw")
    void deleteOnEmptyTreeIsNoOp(){
        assertDoesNotThrow(() -> tree.delete(1));
        assertEquals(0, tree.getSize());
    }

    @Test
    @DisplayName("T1.6: 10k interleaved random inserts and deletes maintain all red-black invariants")
    void interleavedInsertDeleteMaintainsInvariants(){
        // Arrange: insert 0..9999, then delete every even key (5000 deletes),
        // leaving the odd keys as the expected surviving set.
        int n = 10_000;
        List<Integer> keys = new ArrayList<>();
        for(int i = 0; i < n; i++) keys.add(i);
        Collections.shuffle(keys, new Random(42));

        for(int k : keys) tree.insert(k, "v" + k);

        List<Integer> toDelete = new ArrayList<>(keys);
        Collections.shuffle(toDelete, new Random(7));
        for(int k : toDelete){
            if(k % 2 == 0) tree.delete(k);
        }

        // Act
        Node<Integer,String> root = tree.root;
        List<Integer> inOrder = tree.inOrderKeys();

        // Assert
        assertAll("red-black invariants after interleaved insert/delete",
                () -> assertTrue(root == null || !root.isRed(), "(a) root must be black (or tree empty)"),
                () -> assertTrue(root == null || noRedViolation(root), "(b) no red node may have a red child"),
                () -> assertTrue(root == null || blackHeight(root) != -1, "(c) black-height must be equal on every root->NIL path"),
                () -> assertTrue(isSorted(inOrder), "(d) in-order traversal must be strictly ascending"),
                () -> assertEquals(n / 2, tree.getSize(), "(e) exactly the odd keys should remain"),
                () -> {
                    for(int k = 1; k < n; k += 2) assertEquals("v" + k, tree.get(k), "surviving key " + k + " should still be present");
                },
                () -> {
                    for(int k = 0; k < n; k += 2) assertNull(tree.get(k), "deleted key " + k + " should be gone");
                }
        );
    }
}
