package WAL;

import core.Value;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import memTable.MemTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WALTest {
    private File walFile;
    private KeyCodec<Integer> codec;

    @BeforeEach
    void setup(@TempDir Path tempDir){
        this.walFile = tempDir.resolve("test.wal").toFile();
        this.codec = new IntegerKeyCodec();
    }

    @Test
    void replayIntoFreshMemTableReproducesExactState() throws IOException {
        fillWALRecords();

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = wal.replay(memTable);
            assertEquals(WALReplayStopReason.CLEAN_EOF ,result.stopReason(), "Replay with no error should have a clean EOF as replay stop reason.");
        }

        assertAll(
                () -> assertEquals(Value.of("hello".getBytes(StandardCharsets.UTF_8)), memTable.get(1)),
                () -> assertEquals(Value.of("world".getBytes(StandardCharsets.UTF_8)), memTable.get(2)),
                () -> assertNotNull(memTable.get(3), "a deleted key must still return a Value, not null"),
                () -> assertTrue(memTable.get(3).isTombstone(), "key 3 should replay as a tombstone")
        );
    }

    @Test
    void replayOnEmptyWalLeavesMemTableEmpty() throws IOException {
        // walFile is never appended to, so RandomAccessFile("rw") creates it empty on open.
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            // no appends
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.replay(memTable);
        }

        assertNull(memTable.get(1), "nothing was ever appended, memTable should stay empty");
    }

    @Test
    void truncatedTrailingRecordIsSkippedWithoutError() throws IOException {
        fillWALRecords();

        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.setLength(100);
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = assertDoesNotThrow(() -> wal.replay(memTable));
            assertEquals(WALReplayStopReason.TRUNCATED, result.stopReason());
            assertEquals(2, result.recordsRecovered());
            assertEquals(84, result.offset());
        }

        assertAll(
                () -> assertEquals(Value.of("hello".getBytes(StandardCharsets.UTF_8)), memTable.get(1),
                        "record before the truncation point should survive"),
                () -> assertEquals(Value.of("world".getBytes(StandardCharsets.UTF_8)), memTable.get(2),
                        "record before the truncation point should survive"),
                () -> assertNull(memTable.get(3), "the truncated trailing record must not appear at all")
        );
    }

    @Test
    void appendingAfterCloseThrows() throws IOException {
        WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile);
        wal.append(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        wal.close();

        assertThrows(IOException.class,
                () -> wal.append(2, Value.of("b".getBytes(StandardCharsets.UTF_8))),
                "appending after close() should fail since the file handle has been released");
    }

    @Test
    void tryWithResourcesPersistsDataAndReleasesHandleForImmediateReuse() throws IOException {
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.append(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        }

        try (WAL<Integer> reopened = new WAL<>(new IntegerKeyCodec(), walFile)) {
            MemTable<Integer> memTable = new MemTable<>(1000);
            reopened.replay(memTable);

            assertEquals(Value.of("a".getBytes(StandardCharsets.UTF_8)), memTable.get(1));
        }
    }
    
    @Test
    void walFailureLeavesMemTableUntouched() throws IOException{
        WAL<Integer> wal = new WAL<>(codec, walFile);
        MemTable<Integer> memTable = new MemTable<>(1000);
        wal.close();
        
        assertThrows(IOException.class, () -> {
            wal.append(1, Value.of("test".getBytes(StandardCharsets.UTF_8)));
            wal.fsync();
            memTable.put(1, Value.of("test".getBytes(StandardCharsets.UTF_8)));
        });

        assertNull(memTable.get(1), "memTable must not change when the WAP append fails");
    }

    @Test
    void crashBeforeFlushStillRecoversViaWalReplay() throws IOException{
        Value v1 = Value.of("v1".getBytes(StandardCharsets.UTF_8));
        Value v2 = Value.of("v2".getBytes(StandardCharsets.UTF_8));
        Value v3 = Value.of("v3".getBytes(StandardCharsets.UTF_8));
        try(WAL<Integer> wal = new WAL<>(codec, walFile)){
            MemTable<Integer> memTable = new MemTable<>(1000);

            wal.append(1, v1); wal.fsync(); memTable.put(1, v1);
            wal.append(2, v2); wal.fsync(); memTable.put(2, v2);

            wal.close();
            try{
                wal.append(3, v3); // simulate crash
            }catch (IOException e){
                //ignore
            }

        }

        MemTable<Integer> recovered = new MemTable<>(1000);
        try(WAL<Integer> wal = new WAL<>(codec, walFile)){
            wal.replay(recovered);

            assertAll(
                    () -> assertEquals(v1, recovered.get(1), "v1 should be present in recovered memTable"),
                    () -> assertEquals(v2, recovered.get(2), "v2 should be present in recovered memTable"),
                    () -> assertNull(recovered.get(3), "v3 should not be present in recovered memTable")
            );
        }
    }

    @Test
    void replayTruncatedWhenLengthExceedsFileRemainder() throws IOException {
        fillWALRecords();

        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.seek(100);
            raf.writeInt(1 << 28);
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = assertDoesNotThrow(() -> wal.replay(memTable));
            assertEquals(WALReplayStopReason.TRUNCATED, result.stopReason());
            assertEquals(2, result.recordsRecovered());
            assertEquals(84, result.offset());
        }

        assertAll(
                () -> assertEquals(Value.of("hello".getBytes(StandardCharsets.UTF_8)), memTable.get(1),
                        "record before the truncation point should survive"),
                () -> assertEquals(Value.of("world".getBytes(StandardCharsets.UTF_8)), memTable.get(2),
                        "record before the truncation point should survive"),
                () -> assertNull(memTable.get(3), "the truncated trailing record must not appear at all")
        );
    }

    @Test
    void replayReturnInvalidLengthWhenPayloadHasNoLength() throws IOException {
        fillWALRecords();

        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.seek(100);
            raf.writeInt(0);
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = assertDoesNotThrow(() -> wal.replay(memTable));
            assertEquals(WALReplayStopReason.INVALID_LENGTH, result.stopReason());
            assertEquals(2, result.recordsRecovered());
            assertEquals(84, result.offset());
        }

        assertAll(
                () -> assertEquals(Value.of("hello".getBytes(StandardCharsets.UTF_8)), memTable.get(1),
                        "record before the truncation point should survive"),
                () -> assertEquals(Value.of("world".getBytes(StandardCharsets.UTF_8)), memTable.get(2),
                        "record before the truncation point should survive"),
                () -> assertNull(memTable.get(3), "the truncated trailing record must not appear at all")
        );
    }

    @Test
    void replayCorrectlyReturnsBadMagic() throws IOException {
        fillWALRecords();

        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.seek(84);
            raf.write(new byte[33]);
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = assertDoesNotThrow(() -> wal.replay(memTable));
            assertEquals(WALReplayStopReason.BAD_MAGIC, result.stopReason());
            assertEquals(2, result.recordsRecovered());
            assertEquals(84, result.offset());
        }

        assertAll(
                () -> assertEquals(Value.of("hello".getBytes(StandardCharsets.UTF_8)), memTable.get(1),
                        "record before the truncation point should survive"),
                () -> assertEquals(Value.of("world".getBytes(StandardCharsets.UTF_8)), memTable.get(2),
                        "record before the truncation point should survive"),
                () -> assertNull(memTable.get(3), "the truncated trailing record must not appear at all"),
                () -> assertNull(memTable.get(Integer.MIN_VALUE), "Integer.MIN_VALUE record must not appear at all")
        );
    }

    @Test
    void replayCorrectlyReturnsCheckSumMisMatch() throws IOException {
        fillWALRecords();

        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.seek(40);
            raf.write(0);
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = assertDoesNotThrow(() -> wal.replay(memTable));
            assertEquals(WALReplayStopReason.CHECKSUM_MISMATCH, result.stopReason());
            assertEquals(0, result.recordsRecovered());
            assertEquals(0, result.offset());
        }

        assertAll(
                () -> assertTrue(memTable.entries().isEmpty(), "memTable should be empty"),
                () -> assertNull(memTable.get(1),
                        "Corrupted record is not loaded to memTable"),
                () -> assertNull(memTable.get(3), "the truncated trailing record must not appear at all"),
                () -> assertNull(memTable.get(Integer.MIN_VALUE), "Integer.MIN_VALUE record must not appear at all")
        );
    }

    @Test
    void replayCorrectlyReturnsStaleRecordWhenTagRegresses() throws IOException {
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.append(1, Value.of(30, new byte[]{1}));   // tag 30
        }
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.append(2, Value.of(20, new byte[]{2}));   // fresh instance: latestEventId starts at -1, so tag 20
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            ReplayResult result = assertDoesNotThrow(() -> wal.replay(memTable));
            assertEquals(WALReplayStopReason.STALE_RECORD, result.stopReason(),
                    "a record whose tag regresses belongs to an earlier epoch and must stop replay");
            assertEquals(1, result.recordsRecovered());
            assertEquals(38, result.offset(), "the offset must point at the stale record's start");
        }

        assertAll(
                () -> assertEquals(Value.of(new byte[]{1}), memTable.get(1),
                        "the record before the regression is valid and must be recovered"),
                () -> assertNull(memTable.get(2),
                        "the stale record must not be replayed -- doing so would overwrite a newer "
                                + "value with an older one, since MemTable.put does not compare ids")
        );
    }

    void fillWALRecords() throws IOException{
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            // Header size: [Magic]->4 bytes, [tag] -> 8 bytes, [checksum] -> 4 bytes, [length] -> 4 bytes = 20 bytes
            // Payload size: [key] -> 4 bytes, [value] -> 8 bytes (id) + 1 bytes (tombstone flag) + 4 bytes(value length) + 5 bytes (value) = 22 bytes
            wal.append(1, Value.of("hello".getBytes(StandardCharsets.UTF_8))); // 42 bytes
            wal.append(2, Value.of("world".getBytes(StandardCharsets.UTF_8))); // 42 bytes
            wal.append(3, Value.tombstone()); //33 bytes
        }
    }
}
