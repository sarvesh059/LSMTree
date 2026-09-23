package SSTable;

import RBT.Entry;
import core.IndexEntry;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import cursor.EntrySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.DataInput;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SSTableTest {
    private SSTable<Integer> ssTable;
    private File dataFile;
    private File indexFile;
    private File bloomFilterFile;

    @BeforeEach
    void setup(@TempDir Path tempDir){
        this.dataFile = tempDir.resolve("test.sst").toFile();
        this.indexFile = tempDir.resolve("index.sst").toFile();
        this.bloomFilterFile = tempDir.resolve("bloomFilter.sst").toFile();
        this.ssTable = new SSTable<>(new IntegerKeyCodec());
    }

    @Test
    void entriesSurvivesRoundTripWithOrderPreserved() throws IOException {
        Value realValue = Value.of("test".getBytes(StandardCharsets.UTF_8));
        Entry<Integer, Value> realEntry = new Entry<>(0, realValue);

        Value emptyValue = Value.of(new byte[0]);
        Entry<Integer, Value> emptyEntry = new Entry<>(1, emptyValue);

        Value tombstoneValue = Value.tombstone();
        Entry<Integer, Value> tombStoneEntry = new Entry<>(2, tombstoneValue);

        List<Entry<Integer, Value>> inputEntries = List.of(realEntry, emptyEntry, tombStoneEntry);

        ssTable.write(inputEntries, dataFile, indexFile, bloomFilterFile, 10, 0);

        List<Entry<Integer, Value>> outputEntries = ssTable.readAll(dataFile);

        assertAll(
                () -> assertEquals(inputEntries.size(), outputEntries.size(), "output size should be equal to input size"),
                () -> assertEquals(realEntry.getKey(), outputEntries.getFirst().getKey(), "output real entry key should be equal to input real entry key"),
                () -> assertEquals(realEntry.getValue(), outputEntries.getFirst().getValue(), "output real value should be equal to input real value"),
                () -> assertFalse(outputEntries.getFirst().getValue().isTombstone(), "real value should not be a tombstone"),
                () -> assertEquals("test", new String(outputEntries.getFirst().getValue().getData(), StandardCharsets.UTF_8), "output string should be equal to input string"),
                () -> assertEquals(emptyEntry.getKey(), outputEntries.get(1).getKey(), "output empty entry key should be equal to input empty entry key"),
                () -> assertEquals(emptyEntry.getValue(), outputEntries.get(1).getValue(), "output empty value should be equal to input empty value"),
                () -> assertFalse(outputEntries.get(1).getValue().isTombstone(), "empty value should not be a tombstone"),
                () -> assertEquals(tombStoneEntry.getKey(), outputEntries.getLast().getKey(), "output tombstone entry key should be equal to input tombstone entry key"),
                () -> assertEquals(tombStoneEntry.getValue(), outputEntries.getLast().getValue(), "output tombstone value should be equal to input tombstone value"),
                () -> assertTrue(outputEntries.getLast().getValue().isTombstone(), "tombstone value should return isTombstone true")
        );
    }

    @Test
    void emptyInputReturnsEmptyOutput() throws IOException {
        List<Entry<Integer, Value>> inputEntries = new ArrayList<>();
        ssTable.write(inputEntries, dataFile, indexFile, bloomFilterFile, 10, 0);

        List<Entry<Integer, Value>> outputEntries = ssTable.readAll(dataFile);

        assertAll(
                () -> assertEquals(0, outputEntries.size(), "Empty written file returns empty output")
        );
    }

    @Test
    void readAllOnMissingFileThrowsAndDoesNotCreateFile(@TempDir Path tempDir){
        File missing = tempDir.resolve("does-not-exist.sst").toFile();

        assertThrows(FileNotFoundException.class, () -> ssTable.readAll(missing));
        assertFalse(missing.exists(), "readAll should not create a file as a side effect");
    }

    @ParameterizedTest(name = "n={0}, sampleEvery={1} -> expected index count={2}")
    @CsvSource({
            "10, 3, 4",
            "23, 5, 5",
            "1, 5, 1",
            "100, 10, 10",
            "0, 5, 0"
    })
    void indexCountMatchesCeilingFormula(int n, int sampleEvery, int expectedIndexCount) throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(n);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);

        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(expectedIndexCount, loaded.size());
    }

    private List<Entry<Integer, Value>> buildEntries(int n) {
        List<Entry<Integer, Value>> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(new Entry<>(i, Value.of(("v" + i).getBytes(StandardCharsets.UTF_8))));
        }
        return entries;
    }

    @ParameterizedTest(name = "n={0}, sampleEvery={1}")
    @CsvSource({
            "10, 3",
            "23, 5",
            "1, 5",
            "100, 10"
    })
    void firstKeyIsAlwaysSampled(int n, int sampleEvery) throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(n);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);

        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(entries.getFirst().getKey(), loaded.getFirst().getDecodedKey(this.ssTable.keyCodec), "First key should be always sampled");
    }

    @ParameterizedTest(name = "n={0}, sampleEvery={1}")
    @CsvSource({
            "10, 3",
            "23, 5",
            "1, 5",
            "100, 10"
    })
    void loadedOffsetSeeksCorrectKey(int n, int sampleEvery) throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(n);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);

        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        try(RandomAccessFile readStream = new RandomAccessFile(this.dataFile, "r")){
            for(IndexEntry entry: loaded){
                byte[] key = entry.getEncodedKey();
                long offset = entry.getOffset();
                readStream.seek(offset);
                Integer writtenKey = this.ssTable.keyCodec.decode(readStream);
                Integer decodedIndexKey = this.ssTable.keyCodec.decodeKey(key);
                assertEquals(decodedIndexKey, writtenKey, "Index key should match data key at the provided offset");
            }
        }
    }

    @Test
    void scanOnKeyBelowFirstReturnsAbsentWithZeroReads() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(-1, dataFile, loaded);

        assertAll(
                () -> assertNull(result.value()),
                () -> assertEquals(0, result.entriesRead(), "no data file read should occur when key is below every sample")
        );
    }

    @Test
    void scanOnExactSampleKeyReturnsValueOnFirstRead() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(5, dataFile, loaded);

        assertAll(
                () -> assertEquals(entries.get(5).getValue(), result.value()),
                () -> assertEquals(1, result.entriesRead(), "an exact sample-key hit should be found on the very first read")
        );
    }

    @Test
    void scanOnKeyBetweenSamplesReturnsValueWithinBound() throws IOException {
        int sampleEvery = 5;
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(7, dataFile, loaded);

        assertAll(
                () -> assertEquals(entries.get(7).getValue(), result.value()),
                () -> assertTrue(result.entriesRead() <= sampleEvery, "scan must stay within sampleEvery reads")
        );
    }

    @Test
    void scanOnAbsentKeyBetweenSamplesReturnsNullWithinBound() throws IOException {
        List<Entry<Integer, Value>> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            if (i == 7) continue;
            entries.add(new Entry<>(i, Value.of(("v" + i).getBytes(StandardCharsets.UTF_8))));
        }
        int sampleEvery = 5;
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(7, dataFile, loaded);

        assertAll(
                () -> assertNull(result.value(), "key 7 was never inserted, should be absent"),
                () -> assertTrue(result.entriesRead() <= sampleEvery, "scan should remain bounded even when the key is absent")
        );
    }

    @Test
    void scanOnKeyAboveLastReturnsAbsentWithoutThrowing() throws IOException {
        int sampleEvery = 5;
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(999, dataFile, loaded);

        assertAll(
                () -> assertNull(result.value()),
                () -> assertTrue(result.entriesRead() <= sampleEvery, "scan must stay bounded even when running off the end of the file")
        );
    }

    @Test
    void scanOnTombStonedKeyReturnsTombstoneNotNull() throws IOException {
        int sampleEvery = 5;
        List<Entry<Integer, Value>> entries = buildEntries(20);
        entries.set(12, new Entry<>(12, Value.tombstone()));
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(12, dataFile, loaded);

        assertAll(
                () -> assertNotNull(result.value(), "a deleted key must still return a Value, not null"),
                () -> assertTrue(result.value().isTombstone(), "the returned value should be a tombstone"),
                () -> assertTrue(result.entriesRead() <= sampleEvery)
        );
    }

    @ParameterizedTest(name = "n={0}, sampleEvery={1}, key={2}")
    @CsvSource({
            "20, 5, 7",
            "20, 3, 11",
            "50, 10, 999",
            "50, 7, -1",
            "20, 5, 12"
    })
    void scanStaysBoundedAcrossConfigurations(int n, int sampleEvery, int targetEncodedKey) throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(n);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(targetEncodedKey, dataFile, loaded);

        assertTrue(result.entriesRead() <= sampleEvery, "entriesRead=" + result.entriesRead() + " exceeded sampleEvery=" + sampleEvery);
    }

    @Test
    void getReturnsCorrectValueThroughPublicApi() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);

        Value result = ssTable.get(7, dataFile, ssTable.loadIndex(indexFile));

        assertEquals(entries.get(7).getValue(), result);
    }

    @Test
    void scanOnEmptyTableReturnsAbsentWithZeroReads() throws IOException {
        ssTable.write(new ArrayList<>(), dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        SSTable.ScanResult<Value> result = ssTable.scan(0, dataFile, loaded);

        assertAll(
                () -> assertNull(result.value()),
                () -> assertEquals(0, result.entriesRead())
        );
    }

    @Test
    void cursorGivesEntriesInSameOrderAsReadAll() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(10);
        ssTable.write(entries,dataFile, indexFile, bloomFilterFile, 4, 0);

        List<Entry<Integer, Value>> allReadEntries = ssTable.readAll(dataFile);
        try(EntrySource<Integer> cursor = ssTable.openCursor(dataFile)){
            for(Entry<Integer, Value> entry: allReadEntries){
                boolean cursorHasNext = cursor.hasNext();
                Entry<Integer, Value> cursorEntry = cursor.next();
                assertAll(
                        () -> assertTrue(cursorHasNext, "Cursor hasNext should return true when it has entries to iterate"),
                        () -> assertEquals(entry.getKey(), cursorEntry.getKey(), "EntryCursor should return entries in default order"),
                        () -> assertEquals(entry.getValue(), cursorEntry.getValue(), "EntryCursor should have the same value")
                );
            }

            assertFalse(cursor.hasNext(), "hasNext() should be false when there is no entry to read");
        }
    }

    @Test
    void emptyDataFileCursorReturnNoEntries() throws IOException {
        ssTable.write(new ArrayList<>(), dataFile, indexFile, bloomFilterFile, 5, 0);
        try(EntrySource<Integer> cursor = ssTable.openCursor(dataFile)){
            assertFalse(cursor.hasNext(), "EntryCursor hasNext() should return false for empty file");
            assertNull(cursor.next(),"EntryCursor next() return null when there is nothing to read");
        }
    }

    @Test
    void closeReleasesFileHandleForImmediateReuse() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(5);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);

        try (EntrySource<Integer> cursor = ssTable.openCursor(dataFile)) {
            cursor.next();
        }

        try (EntrySource<Integer> reopened = ssTable.openCursor(dataFile)) {
            assertTrue(reopened.hasNext(), "a fresh cursor on the same file should read from the beginning again");
            assertEquals(entries.getFirst().getKey(), reopened.next().getKey(), "reopened cursor should start from the first entry");
        }
    }

    @Test
    void closingPartiallyDrainedCursorDoesNotThrow() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(10);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 4, 0);

        EntrySource<Integer> cursor = ssTable.openCursor(dataFile);
        cursor.next();
        cursor.next();

        assertDoesNotThrow(cursor::close, "closing a cursor that wasn't fully drained should not throw");
    }

    @Test
    void independentCursorsOnSameFileDoNotInterfere() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(5);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);

        try (EntrySource<Integer> cursorA = ssTable.openCursor(dataFile);
             EntrySource<Integer> cursorB = ssTable.openCursor(dataFile)) {

            // advance cursorA well ahead of cursorB
            Entry<Integer, Value> a0 = cursorA.next();
            Entry<Integer, Value> a1 = cursorA.next();
            Entry<Integer, Value> a2 = cursorA.next();

            Entry<Integer, Value> b0 = cursorB.next();

            assertAll(
                    () -> assertEquals(entries.get(0).getKey(), a0.getKey()),
                    () -> assertEquals(entries.get(1).getKey(), a1.getKey()),
                    () -> assertEquals(entries.get(2).getKey(), a2.getKey()),
                    () -> assertEquals(entries.get(0).getKey(), b0.getKey(), "cursorB should independently start from the first entry, unaffected by cursorA's progress")
            );
        }
    }

    @Test
    void getNeverCallsDecodeDuringLookup() throws IOException {
        CountingKeyCodec countingCodec = new CountingKeyCodec();
        SSTable<Integer> countingTable = new SSTable<>(countingCodec);

        List<Entry<Integer, Value>> entries = buildEntries(50);
        countingTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = countingTable.loadIndex(indexFile);

        countingCodec.decodeCallCount = 0; // reset right before the calls actually under test

        countingTable.get(25, dataFile, loaded);   // a hit
        countingTable.get(999, dataFile, loaded);  // a miss

        assertEquals(0, countingCodec.decodeCallCount,
                "get() should never call decode() during a lookup -- it should resolve entirely via readRawEncoded + compareEncoded");
    }

    static class CountingKeyCodec extends IntegerKeyCodec {
        int decodeCallCount = 0;

        @Override
        public Integer decode(DataInput in) throws IOException {
            decodeCallCount++;
            return super.decode(in);
        }
    }

    private List<Integer> rangeKeys(File dataFile, List<IndexEntry> loadedIndex, int low, int high) throws IOException {
        List<Integer> keys = new ArrayList<>();
        byte[] encodedLow = ssTable.keyCodec.encodeKey(low);
        byte[] encodedHigh = ssTable.keyCodec.encodeKey(high);
        try (EntrySource<Integer> cursor = ssTable.rangeCursor(dataFile, loadedIndex, encodedLow, encodedHigh)) {
            while (cursor.hasNext()) keys.add(cursor.next().getKey());
        }
        return keys;
    }

    @Test
    void rangeCursorInsideOneSampleIntervalReturnsExactKeys() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(7, 8, 9), rangeKeys(dataFile, loaded, 7, 9));
    }

    @Test
    void rangeCursorSpanningMultipleSampleIntervalsReturnsExactKeys() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(entries.subList(3, 18).stream().map(Entry::getKey).toList(), rangeKeys(dataFile, loaded, 3, 17));
    }

    @Test
    void rangeCursorWithLowBelowFirstKeyStartsFromBeginning() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(0, 1, 2, 3, 4), rangeKeys(dataFile, loaded, -100, 4));
    }

    @Test
    void rangeCursorWithHighAboveLastKeyReadsToEnd() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(15, 16, 17, 18, 19), rangeKeys(dataFile, loaded, 15, 1000));
    }

    @Test
    void rangeCursorWithLowAboveEveryKeyReturnsEmptyWithoutThrowing() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(10);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(), rangeKeys(dataFile, loaded, 100, 200));
    }

    @Test
    void rangeCursorWithLowGreaterThanHighReturnsEmpty() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(), rangeKeys(dataFile, loaded, 15, 5));
    }

    @Test
    void rangeCursorOnExactBoundaryKeysIncludesBothEndsInclusive() throws IOException {
        List<Entry<Integer, Value>> entries = buildEntries(20);
        ssTable.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(5, 6, 7, 8, 9, 10), rangeKeys(dataFile, loaded, 5, 10));
    }

    @Test
    void rangeCursorOnEmptyTableReturnsEmptyWithoutThrowing() throws IOException {
        ssTable.write(new ArrayList<>(), dataFile, indexFile, bloomFilterFile, 5, 0);
        List<IndexEntry> loaded = ssTable.loadIndex(indexFile);

        assertEquals(List.of(), rangeKeys(dataFile, loaded, 0, 100));
    }
}
