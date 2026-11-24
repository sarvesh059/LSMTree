package lsmtree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import constants.FileConstants;
import core.Segment;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import manifest.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sstable.SSTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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

    @TempDir
    Path dir;

    private Manifest manifest;
    @BeforeEach
    void createManifest(){
        manifest = new Manifest(manifest().toFile());
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
        String liveName = flushOneSegment(1).getFirst().getFileName().toString();
        // A duplicate line makes the rewrite observable: a successful one would collapse it.
        this.manifest.append(liveName);
        List<String> before = this.manifest.readAll();
        assertEquals(List.of(liveName, liveName), before, "precondition: the live name must be listed twice");

        // Manifest.rewrite opens manifest.log.tmp first; a directory there makes it throw.
        Files.createDirectory(dir.resolve(FileConstants.MANIFEST_FILE_NAME+".tmp"));

        try(LSMTree<Integer> tree = openTree()){
            assertArrayEquals(value(1), tree.get(1), "a failed rewrite is housekeeping and must not fail the open");
        }

        assertEquals(before, this.manifest.readAll(), "a failed rewrite leaves the manifest as it was");
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
        assertEquals(1, this.manifest.readAll().size(),
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
            assertThrows(IOException.class, () -> this.manifest.readAll(), () -> "bit " + flippedBit + " (byte " + flippedBit / 8 + ") flipped, yet the manifest was accepted");
        }

        byte[] lastByteFlipped = intact.clone();
        lastByteFlipped[lastByteFlipped.length - 1] ^= 0x01;
        Files.write(manifest(), lastByteFlipped);
        assertRefusedTwiceAndNothingDeleted(segmentFiles, "a bit flip in the last record");
    }
}
