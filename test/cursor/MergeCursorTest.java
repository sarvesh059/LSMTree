package cursor;

import RBT.Entry;
import SSTable.SSTable;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


public class MergeCursorTest {
    SSTable<Integer> table = new SSTable<Integer>(new IntegerKeyCodec());

    @Test
    void hasNextHonorsTombStoneRetentionPolicyRetainsAllTombStones() throws IOException {
        Entry<Integer,Value> tombStoneEntry = new Entry<>(2, Value.tombstone(9));
        Entry<Integer,Value> realEntry = new Entry<>(2, Value.of(3, new byte[1]));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(tombStoneEntry));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(realEntry));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2),(entry) -> true);

        List<Entry<Integer,Value>> iteratedEntries = new ArrayList<>();
        while(cursor.hasNext()){
            iteratedEntries.add(cursor.next());
        }

        assertIterableEquals(List.of(tombStoneEntry), iteratedEntries, "Cursor correctly retains the tombstone after policy asked it to retain");
    }

    @Test
    void hasNextHonorsTombStoneRetentionPolicyDropAllTombStones() throws IOException {
        Entry<Integer,Value> tombStoneEntry = new Entry<>(2, Value.tombstone(9));
        Entry<Integer,Value> realEntry = new Entry<>(2, Value.of(3, new byte[1]));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(tombStoneEntry));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(realEntry));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2),(entry) -> false);

        List<Entry<Integer,Value>> iteratedEntries = new ArrayList<>();
        while(cursor.hasNext()){
            iteratedEntries.add(cursor.next());
        }

        assertEquals(0, iteratedEntries.size(), "Cursor correctly drops the tombstone on drop all policy");
    }

    @Test
    void hasNextCorrectlyDropsTheDuplicateTombStoneEntriesWithLessEventId() throws IOException {
        Entry<Integer,Value> tombStoneEntry = new Entry<>(2, Value.tombstone(3));
        Entry<Integer,Value> realEntry = new Entry<>(2, Value.of(9, new byte[1]));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(tombStoneEntry));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(realEntry));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2),(entry) -> false);

        List<Entry<Integer,Value>> iteratedEntries = new ArrayList<>();
        while(cursor.hasNext()){
            iteratedEntries.add(cursor.next());
        }

        assertIterableEquals(List.of(realEntry), iteratedEntries, "Cursor correctly drops the tombstone entry with for same key having a real entry with greater eventId");
    }

    @Test
    void hasNextCorrectlyDropsAllTombStoneEntries() throws IOException {
        Entry<Integer,Value> entry1 = new Entry<>(1, Value.tombstone(1));
        Entry<Integer,Value> entry2 = new Entry<>(2, Value.tombstone(2));
        Entry<Integer,Value> entry3 = new Entry<>(3, Value.tombstone(3));
        Entry<Integer,Value> realEntry = new Entry<>(4, Value.of(4, new byte[1]));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(entry1));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(entry2));
        EntrySource<Integer> c3 = table.memTableCursor(List.of(entry3));
        EntrySource<Integer> c4 = table.memTableCursor(List.of(realEntry));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2, c3, c4),(entry) -> false);

        List<Entry<Integer,Value>> iteratedEntries = new ArrayList<>();
        while(cursor.hasNext()){
            iteratedEntries.add(cursor.next());
        }

        assertIterableEquals(List.of(realEntry), iteratedEntries, "Cursor correctly drops all unique key tombstone entries");
    }

    @Test
    void hasNextReturnsFalseIfThereIsNoRealEntry() throws IOException {
        Entry<Integer,Value> entry1 = new Entry<>(1, Value.tombstone(1));
        Entry<Integer,Value> entry2 = new Entry<>(2, Value.tombstone(2));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(entry1));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(entry2));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2),(entry) -> false);

        assertFalse(cursor.hasNext(), "hasNext return false when there is no real entry");
    }

    @Test
    void hasNextCorrectlyDropsTombStoneEntriesWithNoRealAncestor() throws IOException {
        Entry<Integer,Value> entry1 = new Entry<>(1, Value.of(1, new byte[1]));
        Entry<Integer,Value> entry2 = new Entry<>(2, Value.of(2, new byte[2]));
        Entry<Integer,Value> entry3 = new Entry<>(3, Value.tombstone(3));
        Entry<Integer,Value> entry4 = new Entry<>(4, Value.tombstone(7));
        Entry<Integer,Value> entry5 = new Entry<>(5, Value.of(5, new byte[1]));
        Entry<Integer,Value> entry6 = new Entry<>(6, Value.of(6, new byte[1]));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(entry1, entry4));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(entry2, entry5));
        EntrySource<Integer> c3 = table.memTableCursor(List.of(entry3, entry6));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2, c3),(entry) -> false);

        List<Entry<Integer,Value>> iteratedEntries = new ArrayList<>();
        while(cursor.hasNext()){
            iteratedEntries.add(cursor.next());
        }

        assertIterableEquals(List.of(entry1, entry2, entry5, entry6), iteratedEntries, "Cursor correctly tombstone entries with no real ancestor");
    }

    @Test
    void hasNextCorrectlyRetainsTombStoneEntries() throws IOException {
        Entry<Integer,Value> entry1 = new Entry<>(1, Value.of(1, new byte[1]));
        Entry<Integer,Value> entry2 = new Entry<>(2, Value.of(2, new byte[2]));
        Entry<Integer,Value> entry3 = new Entry<>(3, Value.tombstone(3));
        Entry<Integer,Value> entry4 = new Entry<>(4, Value.tombstone(7));
        Entry<Integer,Value> entry5 = new Entry<>(5, Value.of(5, new byte[1]));
        Entry<Integer,Value> entry6 = new Entry<>(6, Value.of(6, new byte[1]));
        EntrySource<Integer> c1 = table.memTableCursor(List.of(entry1, entry4));
        EntrySource<Integer> c2 = table.memTableCursor(List.of(entry2, entry5));
        EntrySource<Integer> c3 = table.memTableCursor(List.of(entry3, entry6));
        MergeCursor<Integer> cursor = new MergeCursor<>(List.of(c1, c2, c3),(entry) -> true);

        List<Entry<Integer,Value>> iteratedEntries = new ArrayList<>();
        while(cursor.hasNext()){
            iteratedEntries.add(cursor.next());
        }

        assertIterableEquals(List.of(entry1, entry2, entry3, entry4, entry5, entry6), iteratedEntries, "Cursor correctly retains all tombStone entries");
    }
}
