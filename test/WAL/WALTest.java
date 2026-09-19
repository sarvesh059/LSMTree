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
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.append(1, Value.of("hello".getBytes(StandardCharsets.UTF_8)));
            wal.append(2, Value.of("world".getBytes(StandardCharsets.UTF_8)));
            wal.append(3, Value.tombstone());
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.replay(memTable);
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
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            wal.append(1, Value.of("hello".getBytes(StandardCharsets.UTF_8)));
            wal.append(2, Value.of("world".getBytes(StandardCharsets.UTF_8)));
            wal.append(3, Value.tombstone());
        }

        // simulate mid crash
        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.setLength(30);
        }

        MemTable<Integer> memTable = new MemTable<>(1000);
        try (WAL<Integer> wal = new WAL<>(new IntegerKeyCodec(), walFile)) {
            assertDoesNotThrow(() -> wal.replay(memTable),
                    "a truncated trailing record must be skipped, not thrown");
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
}
