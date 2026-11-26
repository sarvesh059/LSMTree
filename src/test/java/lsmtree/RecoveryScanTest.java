package lsmtree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import constants.FileConstants;
import core.Segment;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import manifest.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sstable.SSTable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;

public class RecoveryScanTest {
    private static final int SAMPLE_EVERY = 5;
    private static final int NEVER_FLUSHES = 1_000_000;
    private static final int RECORD_CRC_OFFSET = 8;
    private static final int RECORD_SEQUENCE_OFFSET = 12;
    private static final int RECORD_LENGTH_OFFSET = 20;
    private static final int RECORD_HEADER_SIZE = 24;

    @TempDir
    Path dir;

    private Manifest openManifest() throws IOException {
        return Manifest.initialiseManifestFile(manifest().toFile());
    }

    private LSMTree<Integer> openTree() throws IOException {
        return openTree(new IntegerKeyCodec());
    }

    private LSMTree<Integer> openTree(KeyCodec<Integer> keyCodec) throws IOException {
        return new LSMTree<>(keyCodec, dir, SAMPLE_EVERY, NEVER_FLUSHES, new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(keyCodec));
    }

    private List<Path> flushOneSegment(int key) throws IOException{
        try(LSMTree<Integer> tree = openTree()){
            tree.put(key, value(key));
            tree.flush();
            return filesOf(tree.segments().getLast());
        }
    }

    private static byte[] value(int i){
        return new byte[]{(byte) i};
    }

    private static List<Path> filesOf(Segment<Integer> segment){
        return List.of(segment.getDataFile().toPath(), segment.getIndexFile().toPath(), segment.getBloomFilterFile().toPath());
    }

    private static void failSegmentWrite(IntegerKeyCodec codec, RuntimeException failure){
        doAnswer(invocation -> {
            boolean insideSegmentWrite = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(SSTable.class.getName()) && frame.getMethodName().equals("write")));
            if(insideSegmentWrite) throw failure;
            return invocation.callRealMethod();
        }).when(codec).encodeKey(any());
    }

    private List<Path> plantSegmentFiles(String uuid) throws IOException{
        List<Path> files = List.of(
                dir.resolve(FileConstants.DATA_FILE_PREFIX + uuid),
                dir.resolve(FileConstants.INDEX_FILE_PREFIX + uuid),
                dir.resolve(FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid)
        );
        for(Path file: files) Files.write(file, new byte[]{1,2,3});
        return files;
    }

    private List<String> segmentFilesNames() throws IOException{
        try(var files = Files.list(dir)){
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(FileConstants.DATA_FILE_PREFIX)
                    || name.startsWith(FileConstants.INDEX_FILE_PREFIX)
                    || name.startsWith(FileConstants.BLOOM_FILTER_FILE_PREFIX))
                    .sorted()
                    .toList();
        }
    }

    private Path manifest(){
        return dir.resolve(FileConstants.MANIFEST_FILE_NAME);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from){
        outer:
        for(int i = from; i <= haystack.length - needle.length; i++){
            for(int j = 0; j < needle.length; j++){
                if(haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void assertRefusedTwiceAndNothingDeleted(List<Path> segmentFiles, String damage) throws IOException{
        byte[] damagedManifest = Files.readAllBytes(manifest());
        for(int open = 1; open <= 2; open++){
            final int attempt = open;
            assertThrows(IOException.class, this::openTree, () -> damage + ": the open must be refused (open " + attempt + ")");
            assertAll(
                    () -> assertAllExist(segmentFiles, damage + ": a refused open must not delete any segment (open " + attempt + ")"),
                    () -> assertArrayEquals(damagedManifest, Files.readAllBytes(manifest()), damage + ": a refused open must leave the manifest byte-identical, or the evidence is lost (open " + attempt + ")")
            );
        }
    }

    private static void assertAllExist(List<Path> paths, String message){
        for(Path path: paths) assertTrue(Files.exists(path), () -> message + ":missing "+ path.getFileName());
    }

    private static void assertNoneExist(List<Path> paths, String message){
        for(Path path: paths) assertFalse(Files.exists(path), () -> message + ": still present "+ path.getFileName());
    }

    private static List<Integer> recordStarts(byte[] manifestBytes){
        List<Integer> starts = new ArrayList<>();
        int position = FileConstants.MANIFEST_FILE_START_LINE.getBytes(StandardCharsets.UTF_8).length;
        while(position < manifestBytes.length){
            starts.add(position);
            position += RECORD_HEADER_SIZE + ByteBuffer.wrap(manifestBytes).getInt(position + RECORD_LENGTH_OFFSET);
        }
        return starts;
    }

    private static long sequenceAt(byte[] manifestBytes, int recordStart){
        return ByteBuffer.wrap(manifestBytes).getLong(recordStart + RECORD_SEQUENCE_OFFSET);
    }

    // Renumbers one record and recomputes its CRC, so the record is intact and only its number is wrong.
    private static void renumber(byte[] manifestBytes, int recordStart, long sequenceNumber){
        int recordEnd = recordStart + RECORD_HEADER_SIZE + ByteBuffer.wrap(manifestBytes).getInt(recordStart + RECORD_LENGTH_OFFSET);
        ByteBuffer.wrap(manifestBytes).putLong(recordStart + RECORD_SEQUENCE_OFFSET, sequenceNumber);
        CRC32C crc = new CRC32C();
        crc.update(manifestBytes, recordStart + RECORD_SEQUENCE_OFFSET, recordEnd - (recordStart + RECORD_SEQUENCE_OFFSET));
        ByteBuffer.wrap(manifestBytes).putInt(recordStart + RECORD_CRC_OFFSET, (int) crc.getValue());
    }

    private List<Path> flushThreeSegments() throws IOException{
        List<Path> segmentFiles = new ArrayList<>();
        try(LSMTree<Integer> tree = openTree()){
            for(int key = 1; key <= 3; key++){
                tree.put(key, value(key));
                tree.flush();
            }
            tree.segments().forEach(segment -> segmentFiles.addAll(filesOf(segment)));
        }
        return segmentFiles;
    }

    private record CompactedManifest(byte[] beforeCompaction, byte[] afterCompaction, List<Path> segmentFiles, List<String> removedNames) {}

    // Flushes A and B, then compacts them into one segment, so the manifest ends with a compaction record:
    // [add output, remove A, remove B]. A crash during that append happens before the inputs are cleaned up,
    // so the inputs' files are put back to match. Returns the manifest's bytes before and after the record,
    // every segment file on disk (inputs and output), and the data-file names of the compacted inputs.
    private CompactedManifest compactTwoSegments() throws IOException{
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();
            byte[] before = Files.readAllBytes(manifest());
            List<String> inputNames = tree.segments().stream().map(segment -> segment.getDataFile().getName()).toList();
            Map<Path, byte[]> inputFiles = new LinkedHashMap<>();
            for(Segment<Integer> segment: tree.segments()){
                for(Path file: filesOf(segment)) inputFiles.put(file, Files.readAllBytes(file));
            }

            tree.sharedCompactionLogic(tree.segments());
            byte[] after = Files.readAllBytes(manifest());

            List<Path> segmentFiles = new ArrayList<>(inputFiles.keySet());
            tree.segments().forEach(segment -> segmentFiles.addAll(filesOf(segment)));
            for(Map.Entry<Path, byte[]> input: inputFiles.entrySet()){
                if(!Files.exists(input.getKey())) Files.write(input.getKey(), input.getValue());
            }
            return new CompactedManifest(before, after, segmentFiles, inputNames);
        }
    }

    private static List<String> dataFileNames(LSMTree<Integer> tree){
        return tree.segments().stream().map(segment -> segment.getDataFile().getName()).toList();
    }

    // One payload entry as ManifestPayload writes it: [name len 4][name][action len 4][action].
    private static void writeEntry(DataOutputStream out, String segmentName, String action) throws IOException{
        byte[] name = segmentName.getBytes(StandardCharsets.UTF_8);
        out.writeInt(name.length);
        out.write(name);
        byte[] actionBytes = action.getBytes(StandardCharsets.UTF_8);
        out.writeInt(actionBytes.length);
        out.write(actionBytes);
    }

    // Replaces the payload of the manifest's last record, keeping its sequence number and recomputing its
    // length and CRC, so the record is intact as far as the frame can tell and only the payload is wrong.
    private static byte[] withLastPayload(byte[] manifestBytes, byte[] payload){
        int lastStart = recordStarts(manifestBytes).getLast();
        ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_SIZE + payload.length);
        record.put(Arrays.copyOfRange(manifestBytes, lastStart, lastStart + RECORD_CRC_OFFSET));
        record.putInt(0);
        record.putLong(sequenceAt(manifestBytes, lastStart));
        record.putInt(payload.length);
        record.put(payload);
        byte[] recordBytes = record.array();
        CRC32C crc = new CRC32C();
        crc.update(recordBytes, RECORD_SEQUENCE_OFFSET, recordBytes.length - RECORD_SEQUENCE_OFFSET);
        ByteBuffer.wrap(recordBytes).putInt(RECORD_CRC_OFFSET, (int) crc.getValue());

        byte[] result = Arrays.copyOf(manifestBytes, lastStart + recordBytes.length);
        System.arraycopy(recordBytes, 0, result, lastStart, recordBytes.length);
        return result;
    }

    @Test
    void missingManifestBesideSegmentFilesRefusesEveryOpen() throws IOException {
        List<Path> segmentFiles = flushOneSegment(1);
        Files.delete(manifest());

        assertThrows(IOException.class, this::openTree,
                "a missing manifest beside segment files means the store's state is unknown");
        assertFalse(Files.exists(manifest()),
                "a refused open must not create a manifest, or the next open would trust it");
        assertThrows(IOException.class, this::openTree,
                "the refusal must hold on every open, not only the first");

        assertAllExist(segmentFiles, "a lost manifest must leave the data recoverable by hand, not delete it");
    }

    @Test
    void emptyManifestWithoutHeaderRefusesEveryOpen() throws IOException {
        List<Path> segmentFiles = flushOneSegment(1);
        Files.write(manifest(), new byte[0]);

        assertThrows(IOException.class, this::openTree,
                "a manifest without its header is not one this store wrote");
        assertEquals(0, Files.size(manifest()),
                "a refused open must leave the manifest exactly as it found it");
        assertThrows(IOException.class, this::openTree,
                "the refusal must hold on every open, not only the first");

        assertAllExist(segmentFiles, "an empty manifest beside segment files is untrustworthy, so nothing is reclaimed");
    }


    @Test
    void orphanSegmentFilesAreReclaimedOnOpen() throws IOException{
        List<Path> live = flushOneSegment(1);
        List<Path> orphan = plantSegmentFiles(UUID.randomUUID().toString());

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1), "the live segment must still serve reads");
        }

        assertNoneExist(orphan, "segment files whose uuid the manifest does not name live are garbage");
        assertAllExist(live, "the live segment's files must be untouched");
    }

    @Test
    void orphanSiblingFilesWithoutADataFileAreReclaimed() throws IOException{
        List<Path> live = flushOneSegment(1);
        List<Path> orphan = plantSegmentFiles(UUID.randomUUID().toString());
        Files.delete(orphan.getFirst());
        List<Path> siblings = orphan.subList(1,3);

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1));
        }

        assertNoneExist(siblings, "an index or bloom file with no data file is still garbage");
        assertAllExist(live, "the live segment's files must be untouched");
    }

    @Test
    void scanNeverTouchesNonSegmentFiles() throws IOException{
        flushOneSegment(1);
        Path notes = Files.writeString(dir.resolve("notes.txt"), "operator notes");
        Path backup = Files.writeString(dir.resolve("backup-"+ FileConstants.DATA_FILE_PREFIX + UUID.randomUUID()), "x");

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1));
        }

        assertAllExist(List.of(notes, backup, dir.resolve(FileConstants.WAL_FILE_NAME), manifest()), "only names that begin with a segment prefix are candidates");
    }

    @Test
    void unparseableSegmentNamesAreSkippedNotDeleted() throws IOException{
        flushOneSegment(1);
        List<Path> foreign = List.of(
                Files.writeString(dir.resolve(FileConstants.DATA_FILE_PREFIX+"not-a-uuid"), "x"),
                Files.writeString(dir.resolve(FileConstants.DATA_FILE_PREFIX+"1-1-1-1-1"), "x"),
                Files.writeString(dir.resolve(FileConstants.INDEX_FILE_PREFIX+ UUID.randomUUID().toString().toUpperCase()), "x")
        );

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1));
        }

        assertAllExist(foreign, "a prefix match whose uuid does not round-trip is foreign, not ours to delete");
    }

    @Test
    void refusedOpenDeletesNothing() throws IOException {
        List<Path> live = flushOneSegment(1);
        Files.delete(live.get(1));
        List<Path> orphan = plantSegmentFiles(UUID.randomUUID().toString());

        assertThrows(IOException.class, this::openTree, "a live segment with a missing index file must refuse the open");
        assertAllExist(orphan, "a refused open must delete nothing: the scan runs after the live-set check");
        assertAllExist(List.of(live.get(0), live.get(2)),"the incomplete live segment's remaining files must stay");
    }

    @Test
    void readOnlyDirectoryWIthAnOrphanStillOpens() throws IOException{
        flushOneSegment(1);
        List<Path> orphan = plantSegmentFiles(UUID.randomUUID().toString());
        assertTrue(dir.toFile().setWritable(false), "precondition: could not make the directory read-only");

        try{
            assumeFalse(Files.isWritable(dir), "running as root: directory permissions are not enforced");

            try(LSMTree<Integer> tree = openTree()){
                assertArrayEquals(value(1), tree.get(1), "reclamation is housekeeping; it must never fail open");
            }
            assertAllExist(orphan, "the delete failed, so the orphan remains for a later open");
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    @Test
    void orphanWithABlockedFileStillHasEveryOtherFileReclaimed() throws IOException{
        flushOneSegment(1);
        String uuid = UUID.randomUUID().toString();
        List<Path> filesOfB = plantSegmentFiles(uuid);
        Path dataOfB = filesOfB.get(0);
        Path indexOfB = filesOfB.get(1);
        Path bloomOfB = filesOfB.get(2);

        // delete indexOfB file then recreate it as directory with blocker inside it
        // so deleteIfExists on indexOfB throws DirectoryNotEmptyException
        Files.delete(indexOfB);
        Files.createDirectories(indexOfB.resolve("blocker"));

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1), "a failed delete must not fail the open");
        }
        assertAll(
                () -> assertFalse(Files.exists(dataOfB), "the data file must still be deleted"),
                () -> assertFalse(Files.exists(bloomOfB), "every file must be attempted, not only those before the one that failed"),
                () -> assertTrue(Files.exists(indexOfB), "sanity: the blocked file is still there")
        );
    }

    @Test
    void failedManifestRewritesStillOpens() throws IOException{
        // Two flushes in one session leave several records; the open's rewrite would collapse them into one.
        // That difference in bytes is what makes a rewrite observable.
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();
        }
        byte[] before = Files.readAllBytes(manifest());

        // Manifest.rewrite opens manifest.log.tmp first; a directory there makes it throw.
        Path blocker = dir.resolve(FileConstants.MANIFEST_FILE_NAME + ".tmp");
        Files.createDirectory(blocker);

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1), "a failed rewrite is housekeeping and must not fail the open");
            assertArrayEquals(value(2), tree.get(2), "a failed rewrite is housekeeping and must not fail the open");
        }
        assertArrayEquals(before, Files.readAllBytes(manifest()), "a failed rewrite leaves the manifest as it was");

        // Sanity: without the blocker the rewrite does change the bytes, so the assertion above can fail.
        Files.delete(blocker);
        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(2), tree.get(2));
        }
        assertFalse(Arrays.equals(before, Files.readAllBytes(manifest())),
                "precondition: a successful rewrite must be visible in the manifest's bytes");
    }

    @Test
    void failedFlushKeepsItsKeysInTheWalAcrossAReopen() throws IOException{
        IntegerKeyCodec codec = spy(new IntegerKeyCodec());
        try(LSMTree<Integer> tree = openTree(codec)){
            for(int i=1;i<=3;i++) tree.put(i, value(i));

            failSegmentWrite(codec, new UncheckedIOException(new IOException("injected: write failure")));
            assertThrows(UncheckedIOException.class, tree::flush);
            doCallRealMethod().when(codec).encodeKey(any());

            assertEquals(List.of(), segmentFilesNames(), "the failed flush must clean up its own files in-session");
        }
        try(LSMTree<Integer> reopened = openTree()){
            for(int i=1;i<=3;i++){
                assertArrayEquals(value(i), reopened.get(i), "the flush never committed, so the WAL was not reset and key "+i+" must replay");
            }
        }
    }

    @ParameterizedTest(name = "leftover tmp = \"{0}\"")
    @ValueSource(strings = {"", "manifest-start\n", "manifest-st", "dataFile-x;\ndataFile-y;\n"})
    void creationInterruptedBeforeItsRenameLeavesAStoreThatOpens(String leftover) throws IOException {
        assertFalse(Files.exists(manifest()), "precondition: the store is new, so the creation path must run");

        Path leftoverTmp = dir.resolve(FileConstants.MANIFEST_FILE_NAME + ".tmp");
        Files.writeString(leftoverTmp, leftover, StandardCharsets.UTF_8);

        try (LSMTree<Integer> tree = openTree()) {
            tree.put(1, value(1));
            tree.flush();
        }
        try (LSMTree<Integer> tree = openTree()) {
            assertArrayEquals(value(1), tree.get(1),
                    "a creation that never reached its rename committed nothing, so the store is simply new");
        }

        assertFalse(Files.exists(leftoverTmp), "the leftover must be replaced and renamed away, not left behind");
        assertEquals(1, openManifest().readAll().size(),
                "nothing from the leftover may reach the manifest; only the flush's entry");
    }

    @Test
    void manifestDamagedMidFileRefusesToOpen() throws IOException{
        List<Path> segmentFiles = new ArrayList<>();
        String dataFileOfA;
        String dataFileOfB;
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(1));
            tree.flush();
            dataFileOfA = tree.segments().getFirst().getDataFile().getName();
            tree.put(2, value(2));
            tree.flush();
            dataFileOfB = tree.segments().getLast().getDataFile().getName();
            tree.segments().forEach(segment -> segmentFiles.addAll(filesOf(segment)));
        }

        byte[] bytes = Files.readAllBytes(manifest());
        int nameOfA = indexOf(bytes, dataFileOfA.getBytes(StandardCharsets.UTF_8), 0);
        int nameOfB = indexOf(bytes, dataFileOfB.getBytes(StandardCharsets.UTF_8), 0);
        assertTrue(nameOfA >= 0 && nameOfB > nameOfA, "precondition: A's record must exist and be followed by B's");

        bytes[nameOfA + dataFileOfA.length() / 2] ^= 0x01;
        Files.write(manifest(), bytes);

        assertRefusedTwiceAndNothingDeleted(segmentFiles, "a bit flip in A's name with B's valid record after it");
    }

    @Test
    void tornFinalManifestRecordRefusesToOpen() throws IOException{
        List<Path> segmentFiles = new ArrayList<>();
        byte[] beforeB;
        byte[] afterB;
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(1));
            tree.flush();
            beforeB = Files.readAllBytes(manifest());
            tree.put(2, value(2));
            tree.flush();
            afterB = Files.readAllBytes(manifest());
            tree.segments().forEach(segment -> segmentFiles.addAll(filesOf(segment)));
        }
        assertTrue(afterB.length > beforeB.length + 1, "precondition: B's flush must have appended a record");

        // Every length a crash mid-append could leave: from one byte of B's record to all but its last byte.
        for(int length = beforeB.length + 1; length < afterB.length; length++){
            Files.write(manifest(), Arrays.copyOf(afterB, length));
            assertRefusedTwiceAndNothingDeleted(segmentFiles, "B's record torn to " + (length - beforeB.length) + " bytes");
        }

        // The file size reached disk but B's bytes did not: the record reads back as zeros.
        byte[] zeroTail = afterB.clone();
        Arrays.fill(zeroTail, beforeB.length, zeroTail.length, (byte) 0);
        Files.write(manifest(), zeroTail);
        assertRefusedTwiceAndNothingDeleted(segmentFiles, "B's record zero-filled at full length");
    }

    @Test
    void everySingleBitFlipInTheManifestIsRefused() throws IOException{
        List<Path> segmentFiles = new ArrayList<>();
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();
            tree.segments().forEach(segment -> segmentFiles.addAll(filesOf(segment)));
        }
        byte[] intact = Files.readAllBytes(manifest());

        for(int bit = 0; bit < intact.length * 8; bit++){
            byte[] flipped = intact.clone();
            flipped[bit / 8] ^= (byte) (1 << (bit % 8));
            Files.write(manifest(), flipped);
            final int flippedBit = bit;
            assertThrows(IOException.class, () -> openManifest().readAll(), () -> "bit " + flippedBit + " (byte " + flippedBit / 8 + ") flipped, yet the manifest was accepted");
        }

        byte[] lastByteFlipped = intact.clone();
        lastByteFlipped[lastByteFlipped.length - 1] ^= 0x01;
        Files.write(manifest(), lastByteFlipped);
        assertRefusedTwiceAndNothingDeleted(segmentFiles, "a bit flip in the last record");
    }

    @Test
    void aGapInTheSequenceNumbersRefusesToOpen() throws IOException{
        List<Path> segmentFiles = flushThreeSegments();
        byte[] manifestBytes = Files.readAllBytes(manifest());
        List<Integer> starts = recordStarts(manifestBytes);
        assertEquals(4, starts.size(), "precondition: the open's snapshot plus one record per flush");

        // Shift the middle record and every record after it up by one: the only fault is the skipped number.
        for(int i = 2; i < starts.size(); i++){
            renumber(manifestBytes, starts.get(i), sequenceAt(manifestBytes, starts.get(i)) + 1);
        }
        Files.write(manifest(), manifestBytes);

        assertRefusedTwiceAndNothingDeleted(segmentFiles, "intact records from the middle on, numbered one too high");
    }

    @Test
    void aSequenceNumberThatDoesNotIncreaseRefusesToOpen() throws IOException{
        List<Path> segmentFiles = flushThreeSegments();
        byte[] manifestBytes = Files.readAllBytes(manifest());
        List<Integer> starts = recordStarts(manifestBytes);
        assertEquals(4, starts.size(), "precondition: the open's snapshot plus one record per flush");

        // Shift the middle record and every record after it down by one: the only fault is the repeated number.
        for(int i = 2; i < starts.size(); i++){
            renumber(manifestBytes, starts.get(i), sequenceAt(manifestBytes, starts.get(i)) - 1);
        }
        Files.write(manifest(), manifestBytes);

        assertRefusedTwiceAndNothingDeleted(segmentFiles, "intact records from the middle on, numbered one too low");
    }

    @Test
    void sequenceNumbersContinueAcrossTheSnapshotAtOpen() throws IOException{
        flushThreeSegments();
        byte[] before = Files.readAllBytes(manifest());
        List<Integer> startsBefore = recordStarts(before);
        long lastBefore = sequenceAt(before, startsBefore.getLast());

        try(LSMTree<Integer> tree = openTree()){
            tree.put(4, value(4));
            tree.flush();
        }

        byte[] after = Files.readAllBytes(manifest());
        List<Long> numbersAfter = recordStarts(after).stream().map(start -> sequenceAt(after, start)).toList();
        assertEquals(List.of(lastBefore + 1, lastBefore + 2), numbersAfter,
                "the snapshot takes the next number after the old file's last, and the flush the one after that");
    }

    @Test
    void aCompactionAppendsExactlyOneRecordAndKeepsTheOldBytes() throws IOException{
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();
            byte[] before = Files.readAllBytes(manifest());

            tree.sharedCompactionLogic(tree.segments());
            byte[] after = Files.readAllBytes(manifest());

            assertArrayEquals(before, Arrays.copyOf(after, before.length),
                    "a compaction must append, leaving every byte already written untouched");
            assertEquals(recordStarts(before).size() + 1, recordStarts(after).size(),
                    "one logical change is one record, however many segments it removes and adds");
            assertEquals(tree.segments().stream().map(segment -> segment.getDataFile().getName()).toList(),
                    openManifest().readAll(),
                    "replaying the manifest must give exactly the segments live in memory");
        }
    }

    @Test
    void aCompactedStoreReopensWithTheMergedSegmentOnly() throws IOException{
        // A holds 1 and an old 2; B holds the newer 2. After compacting A and B, only the merged output is live.
        try(LSMTree<Integer> tree = openTree()){
            tree.put(1, value(10));
            tree.put(2, value(20));
            tree.flush();
            tree.put(2, value(21));
            tree.flush();
            assertEquals(2, tree.segmentCount(), "precondition: two segments to compact");

            tree.sharedCompactionLogic(tree.segments());
            assertEquals(1, tree.segmentCount(), "precondition: the compaction merged them into one");
        }

        try(LSMTree<Integer> tree = openTree()){
            assertEquals(1, tree.segmentCount(), "reopen must not bring back the compacted inputs");
            assertArrayEquals(value(10), tree.get(1));
            assertArrayEquals(value(21), tree.get(2), "the newer value must win after reopen");
        }
    }

    @Test
    void everySingleBitFlipInACompactionRecordIsRefused() throws IOException{
        CompactedManifest compacted = compactTwoSegments();
        byte[] intact = compacted.afterCompaction();
        int recordStart = compacted.beforeCompaction().length;
        assertTrue(intact.length > recordStart + 1, "precondition: the compaction must have appended a record");

        for(int bit = recordStart * 8; bit < intact.length * 8; bit++){
            byte[] flipped = intact.clone();
            flipped[bit / 8] ^= (byte) (1 << (bit % 8));
            Files.write(manifest(), flipped);
            final int flippedBit = bit;
            assertThrows(IOException.class, () -> openManifest().readAll(),
                    () -> "bit " + flippedBit + " (byte " + (flippedBit / 8 - recordStart) + " of the compaction record) flipped, yet the manifest was accepted");
        }

        // A flip inside a removed name is the dangerous one: misread, it would keep a compacted input live.
        byte[] removedName = compacted.removedNames().getFirst().getBytes(StandardCharsets.UTF_8);
        int removedEntry = indexOf(intact, removedName, recordStart);
        assertTrue(removedEntry > 0, "precondition: the compaction record must name the compacted input it removes");
        byte[] flippedRemove = intact.clone();
        flippedRemove[removedEntry + removedName.length - 1] ^= 0x01;
        Files.write(manifest(), flippedRemove);
        assertRefusedTwiceAndNothingDeleted(compacted.segmentFiles(), "a bit flip in a remove entry of the compaction record");
    }

    @Test
    void tornCompactionRecordRefusesToOpen() throws IOException{
        CompactedManifest compacted = compactTwoSegments();
        byte[] before = compacted.beforeCompaction();
        byte[] after = compacted.afterCompaction();
        assertTrue(after.length > before.length + 1, "precondition: the compaction must have appended a record");

        // Every length a crash mid-append could leave: from one byte of the record to all but its last byte.
        for(int length = before.length + 1; length < after.length; length++){
            Files.write(manifest(), Arrays.copyOf(after, length));
            assertRefusedTwiceAndNothingDeleted(compacted.segmentFiles(), "the compaction record torn to " + (length - before.length) + " bytes");
        }

        // The file size reached disk but the record's bytes did not: it reads back as zeros.
        byte[] zeroTail = after.clone();
        Arrays.fill(zeroTail, before.length, zeroTail.length, (byte) 0);
        Files.write(manifest(), zeroTail);
        assertRefusedTwiceAndNothingDeleted(compacted.segmentFiles(), "the compaction record zero-filled at full length");
    }

    @Test
    void replayingTheManifestGivesTheSegmentsLiveInMemory() throws IOException{
        // A fixed seed keeps the sequence of flushes and compactions the same on every run.
        Random random = new Random(20261126L);
        Map<Integer, byte[]> expected = new HashMap<>();
        LSMTree<Integer> tree = openTree();
        try{
            for(int step = 1; step <= 60; step++){
                List<Segment<Integer>> live = tree.segments();
                if(live.size() >= 2 && random.nextInt(10) < 4){
                    // Compact a random subset of at least two live segments, not necessarily adjacent ones.
                    List<Segment<Integer>> shuffled = new ArrayList<>(live);
                    Collections.shuffle(shuffled, random);
                    tree.sharedCompactionLogic(shuffled.subList(0, 2 + random.nextInt(shuffled.size() - 1)));
                }else{
                    for(int write = 0; write < 1 + random.nextInt(3); write++){
                        int key = random.nextInt(20);
                        byte[] value = value(step * 10 + write);
                        tree.put(key, value);
                        expected.put(key, value);
                    }
                    tree.flush();
                }

                final int completedStep = step;
                assertEquals(dataFileNames(tree), openManifest().readAll(),
                        () -> "after step " + completedStep + ", replaying the manifest must give exactly the segments live in memory, in order");

                if(step % 15 == 0){
                    // Reopen mid-sequence, so the replay also runs across the open's snapshot and later appends.
                    List<String> beforeClose = dataFileNames(tree);
                    tree.close();
                    tree = openTree();
                    assertEquals(beforeClose, dataFileNames(tree),
                            () -> "after the reopen at step " + completedStep + ", the live segments must be those live before close");
                }
            }

            for(Map.Entry<Integer, byte[]> entry: expected.entrySet()){
                assertArrayEquals(entry.getValue(), tree.get(entry.getKey()), "key " + entry.getKey() + " must keep its latest value");
            }
        } finally {
            tree.close();
        }
    }

    @Test
    void anIntactRecordWhosePayloadDoesNotDecodeRefusesToOpen() throws IOException{
        List<Path> segmentFiles = flushOneSegment(1);
        String liveName = segmentFiles.getFirst().getFileName().toString();
        byte[] intact = Files.readAllBytes(manifest());

        // Each payload names a segment whose files exist, so accepting it by mistake would let the open succeed.
        Map<String, byte[]> undecodable = new LinkedHashMap<>();

        ByteArrayOutputStream trailing = new ByteArrayOutputStream();
        DataOutputStream trailingOut = new DataOutputStream(trailing);
        trailingOut.writeInt(1);
        writeEntry(trailingOut, liveName, "ADDED");
        trailingOut.writeInt(7);
        undecodable.put("bytes left over after the last entry", trailing.toByteArray());

        ByteArrayOutputStream unknownAction = new ByteArrayOutputStream();
        DataOutputStream unknownActionOut = new DataOutputStream(unknownAction);
        unknownActionOut.writeInt(1);
        writeEntry(unknownActionOut, liveName, "MOVED");
        undecodable.put("an action no constant declares", unknownAction.toByteArray());

        ByteArrayOutputStream shortCount = new ByteArrayOutputStream();
        DataOutputStream shortCountOut = new DataOutputStream(shortCount);
        shortCountOut.writeInt(2);
        writeEntry(shortCountOut, liveName, "ADDED");
        undecodable.put("a count promising more entries than follow", shortCount.toByteArray());

        for(Map.Entry<String, byte[]> payload: undecodable.entrySet()){
            byte[] damaged = withLastPayload(intact, payload.getValue());
            Files.write(manifest(), damaged);
            for(int open = 1; open <= 2; open++){
                final int attempt = open;
                // Any exception counts: the rule is that such a record refuses the open, not which type says so.
                assertThrows(Exception.class, this::openTree,
                        () -> payload.getKey() + ": the open must be refused (open " + attempt + ")");
                assertAll(
                        () -> assertAllExist(segmentFiles, payload.getKey() + ": a refused open must not delete any segment (open " + attempt + ")"),
                        () -> assertArrayEquals(damaged, Files.readAllBytes(manifest()), payload.getKey() + ": a refused open must leave the manifest byte-identical (open " + attempt + ")")
                );
            }
        }
    }
}
