package memTable;

import RBT.Entry;
import core.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MemTableTest {

    MemTable<Integer> memTable;

    @BeforeEach
    void setUp(){
        memTable = new MemTable<>(100);
    }

    @Test
    void getReturnsCorrectValue(){
        String str = "test";
        Value stringValue = Value.of(str.getBytes(StandardCharsets.UTF_8));
        Value emptyValue = Value.of(new byte[0]);
        Value tombstone = Value.tombstone();

        memTable.put(1, stringValue);
        memTable.put(2, emptyValue);
        memTable.put(3, tombstone);

        assertAll(
                () -> assertEquals(stringValue, memTable.get(1), "get should return correct real value"),
                () -> assertEquals(emptyValue, memTable.get(2), "get should return correct empty value"),
                () -> assertEquals(tombstone, memTable.get(3), "get should return correct tombstone value")
        );
    }

    @Test
    void putOverwritesTheExistingValue(){
        String str = "test";
        Value stringValue = Value.of(str.getBytes(StandardCharsets.UTF_8));
        memTable.put(1, stringValue);

        String newStr = "development";
        Value newStringValue = Value.of(newStr.getBytes(StandardCharsets.UTF_8));
        memTable.put(1, newStringValue);

        Value retrievedValue = memTable.get(1);

        assertAll(
                () -> assertEquals(newStr, new String(retrievedValue.getData(), StandardCharsets.UTF_8), "put should overwrite the existing value"),
                () -> assertFalse(retrievedValue.isTombstone(), "Real retrieved value should not have a tombstone")
        );
    }

    @Test
    void getAfterDeleteReturnATombstoneValue(){
        Value emptyValue = Value.of(new byte[0]);
        memTable.put(1, emptyValue);

        Value retrievedValueBeforeDelete = memTable.get(1);

        memTable.delete(1);

        Value retrievedValueAfterDelete = memTable.get(1);

        assertAll(
                () -> assertFalse(emptyValue.isTombstone(), "empty value should not have a tombstone"),
                () -> assertEquals(emptyValue, retrievedValueBeforeDelete, "retrieved value should be equal to original value"),
                () -> assertNotNull(retrievedValueAfterDelete,"retrieved value after delete should not be a null"),
                () -> assertTrue(retrievedValueAfterDelete.isTombstone(), "retrieved value after delete should be a tombstone")
        );
    }

    @Test
    void deleteOnUnseenKeyReturnTombstoneValue(){
        memTable.delete(1);

        Value deletedValue = memTable.get(1);

        assertAll(
                () -> assertNotNull(deletedValue, "get should not return a null value"),
                () -> assertTrue(deletedValue.isTombstone(), "deleted value should be a tombstone")
        );
    }

    @Test
    void newMemTableHaveZeroSize(){
        assertEquals(0, memTable.getSizeInBytes(), "MemTable should be initialised with size=0");
    }

    @Test
    void insertNewKeyIncreasesSizeCounter(){
        int sizeBeforeInsert = memTable.getSizeInBytes();

        memTable.put(1, Value.of("test".getBytes(StandardCharsets.UTF_8)));

        int sizeAfterInsert = memTable.getSizeInBytes();

        assertTrue(sizeAfterInsert > sizeBeforeInsert, "Inserting data in memTable should increase size counter");
    }

    @Test
    void overWritingValuesWithLargerSizeValueIncreaseSizeByDifference(){
        Value oldValue = Value.of("test".getBytes(StandardCharsets.UTF_8));
        int oldValueSize = oldValue.getSizeInBytes();
        memTable.put(1, oldValue);
        int oldTableSize = memTable.getSizeInBytes();

        Value newValue = Value.of("testTestTestTest".getBytes(StandardCharsets.UTF_8));
        int newValueSize = newValue.getSizeInBytes();
        memTable.put(1, newValue);
        int newTableSize = memTable.getSizeInBytes();

        assertAll(
                () -> assertTrue(newTableSize > oldTableSize,"new table size should be greater than old table size"),
                () -> assertEquals(newValueSize-oldValueSize,newTableSize-oldTableSize, "overWriting key with larger value should increase size by difference")
        );
    }

    @Test
    void getOnAbsentKeyReturnsNull(){
        assertNull(memTable.get(1), "get on a key that was never put should return null");
    }

    @Test
    void overWritingValueWithSmallerValueDecreasesSizeByDifference(){
        Value oldValue = Value.of("testTestTestTest".getBytes(StandardCharsets.UTF_8));
        memTable.put(1, oldValue);
        int oldValueSize = oldValue.getSizeInBytes();
        int oldTableSize = memTable.getSizeInBytes();

        Value newValue = Value.of("test".getBytes(StandardCharsets.UTF_8));
        int newValueSize = newValue.getSizeInBytes();
        memTable.put(1, newValue);
        int newTableSize = memTable.getSizeInBytes();

        assertAll(
                () -> assertTrue(newTableSize < oldTableSize, "new table size should be smaller than old table size"),
                () -> assertEquals(newValueSize - oldValueSize, newTableSize - oldTableSize, "overwriting key with smaller value should decrease size by exactly the difference")
        );
    }

    @Test
    void deleteDecreasesSizeCounter(){
        Value value = Value.of("test".getBytes(StandardCharsets.UTF_8));
        memTable.put(1, value);
        int sizeBeforeDelete = memTable.getSizeInBytes();

        memTable.delete(1);
        int sizeAfterDelete = memTable.getSizeInBytes();

        int tombstoneSize = Value.tombstone().getSizeInBytes();
        int expectedSize = sizeBeforeDelete - value.getSizeInBytes() + tombstoneSize;

        assertEquals(expectedSize, sizeAfterDelete, "deleting a key should shrink the size counter by (old value size - tombstone size)");
    }

    @Test
    void entriesReturnsKeysInAscendingOrder(){
        memTable.put(3, Value.of("c".getBytes(StandardCharsets.UTF_8)));
        memTable.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        memTable.put(2, Value.of("b".getBytes(StandardCharsets.UTF_8)));

        List<Entry<Integer, Value>> entries = memTable.entries();

        assertEquals(List.of(1, 2, 3), entries.stream().map(Entry::getKey).toList(),
                "entries should be sorted by key ascending");
    }

    @Test
    void entriesIncludesTombStonedKeys(){
        memTable.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        memTable.delete(1);

        List<Entry<Integer, Value>> entries = memTable.entries();

        assertAll(
                () -> assertEquals(1, entries.size(), "deleted key should still appear in entries, ready to flush"),
                () -> assertTrue(entries.getFirst().getValue().isTombstone(), "entry for a deleted key should hold a tombstone value")
        );
    }

    @Test
    void isFullTripsOnceThresholdIsCrossed(){
        MemTable<Integer> smallTable = new MemTable<>(5);
        assertFalse(smallTable.isFull(), "a fresh memTable should not be full");

        smallTable.put(1, Value.of("test".getBytes(StandardCharsets.UTF_8)));
        assertTrue(smallTable.isFull(), "memTable should be full once the size counter reaches or exceeds the threshold");
    }
}
