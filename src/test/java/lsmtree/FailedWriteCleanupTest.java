package lsmtree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.MergeStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import constants.FileConstants;
import core.Segment;
import core.SegmentFiles;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sstable.SSTable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class FailedWriteCleanupTest {
    private static final int SAMPLE_EVERY = 5;
    private static final int NEVER_FLUSHES = 1_000_000;
    private static final int FOUR_PUTS_FLUSH = 70;

    @TempDir
    Path dir;

    private LSMTree<Integer> openTree(KeyCodec<Integer> codec, int threshold) throws IOException {
        return new LSMTree<>(codec, dir, SAMPLE_EVERY, threshold, new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(codec));
    }

    private byte[] value(int i){
        return new byte[]{(byte)i};
    }

    private static void failSegmentWrites(IntegerKeyCodec codec, RuntimeException failure){
        doAnswer(invocation -> {
            boolean insideSegmentWrite = StackWalker.getInstance().walk(frames ->
                    frames.anyMatch(frame ->
                            frame.getClassName().equals(SSTable.class.getName())
                                    && frame.getMethodName().equals("write")
                    )
            );
            if(insideSegmentWrite) throw failure;
            return invocation.callRealMethod();
        }).when(codec).encodeKey(any());
    }

    private List<String> segmentFileNames() throws IOException{
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

    @Test
    void failedFlushLeavesNoFilesAndRethrowsTheOriginal() throws IOException{
        IntegerKeyCodec codec = spy(new IntegerKeyCodec());
        try(LSMTree<Integer> tree = openTree(codec, NEVER_FLUSHES)){
            for(int i=1;i<=3;i++) tree.put(i, value(i));

            UncheckedIOException injected = new UncheckedIOException(new IOException("Injected write failure"));
            failSegmentWrites(codec, injected);

            UncheckedIOException thrown = assertThrows(UncheckedIOException.class, tree::flush);
            doCallRealMethod().when(codec).encodeKey(any());

            assertSame(injected, thrown, "cleanup must not replace or wrap the original failure");
            assertEquals(List.of(), segmentFileNames(), "a failed flush must remove the files it created");
            assertEquals(0, tree.segmentCount(), "nothing was installed");
            for(int i=1;i<=3;i++){
                assertArrayEquals(value(i), tree.get(i), "key "+i+" is still in the memTable");
            }
        }
    }

    @Test
    void repeatedFailingPutsDoNotAccumulateFiles() throws IOException{
        IntegerKeyCodec codec = spy(new IntegerKeyCodec());
        try(LSMTree<Integer> tree = openTree(codec, FOUR_PUTS_FLUSH)){
            for(int i=1; i<=3; i++) tree.put(i, value(i));

            failSegmentWrites(codec, new UncheckedIOException(new IOException("injected: disk full")));

            for(int i=4;i <=9; i++){
                final int key=i;
                assertThrows(UncheckedIOException.class, () -> tree.put(key, value(key)), "put "+ key + " triggers a flush, which fails");
            }
            doCallRealMethod().when(codec).encodeKey(any());

            assertEquals(List.of(), segmentFileNames(), "six failed flushes must leave no files, not six partial triples");
            tree.flush();
            assertEquals(1, tree.segmentCount(), "once the fault clears, the next flush succeeds");
            for(int i=1;i <=9;i++) assertArrayEquals(value(i), tree.get(i), "key "+i);
        }
    }

    @Test
    void failureAfterTheManifestAppendKeepsTheFiles() throws IOException{
        String liveName;
        List<String> orphanedNames;
        try(LSMTree<Integer> tree = openTree(new IntegerKeyCodec(), NEVER_FLUSHES)){
            tree.put(1, value(1));
            tree.flush();
            liveName = tree.segments().getFirst().getDataFile().getName();

            File manifest = manifest().toFile();
            assertTrue(manifest.setWritable(false), "precondition: could not make the manifest read-only");

            try {
                assumeFalse(Files.isWritable(manifest()), "running as root: file permissions are not enforced");

                tree.put(2, value(2));
                assertThrows(IOException.class, tree::flush, "the manifest append must fail");

                List<String> onDisk = segmentFileNames();
                orphanedNames = onDisk.stream()
                        .filter(name -> name.startsWith(FileConstants.DATA_FILE_PREFIX) && !name.equals(liveName))
                        .toList();

                assertEquals(1, orphanedNames.size(), () -> "the output must stay on disk past the commit point: "+ onDisk);
            } finally {
                manifest.setWritable(true);
            }
        }

        try (LSMTree<Integer> tree = openTree(new IntegerKeyCodec(), NEVER_FLUSHES)){
            assertEquals(1, tree.segmentCount(), "the unrecorded output is not live");
            assertArrayEquals(value(1), tree.get(1));
            assertArrayEquals(value(2), tree.get(2), "the WAL was never reset, so key 2 replays");
            assertFalse(Files.exists(dir.resolve(orphanedNames.getFirst())), "the scan at open reclaims what the in-session cleanup had to leave");
        }
    }

    @Test
    void failedCompactionLeavesNoOutputFiles() throws IOException {
        List<Path> createdOutputs = new ArrayList<>();
        @SuppressWarnings("unchecked")
        MergeStrategy<Integer> failingMerge = mock(MergeStrategy.class);
        when(failingMerge.merge(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            Supplier<SegmentFiles> outputs = invocation.getArgument(2);
            for (int output = 0; output < 2; output++) {
                SegmentFiles files = outputs.get();
                for (File file : List.of(files.dataFile(), files.indexFile(), files.bloomFilterFile())) {
                    Files.createFile(file.toPath());
                    createdOutputs.add(file.toPath());
                }
            }
            throw new IOException("injected: merge failed after creating two outputs");
        });

        try (LSMTree<Integer> tree = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, NEVER_FLUSHES, new FullCompactStrategy<>(1000), failingMerge)) {
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();

            assertThrows(IOException.class, () -> tree.sharedCompactionLogic(tree.segments()));
            assertEquals(6, createdOutputs.size(), "precondition: the merge created two outputs before failing");
            for (Path output : createdOutputs) {
                assertFalse(Files.exists(output), () -> "a failed compaction must remove its outputs: " + output.getFileName());
            }

            assertEquals(2, tree.segmentCount(), "the inputs stay live");
            assertArrayEquals(value(1), tree.get(1));
            assertArrayEquals(value(2), tree.get(2));
        }
    }

    @Test
    void failedCompactionReleasesItsPinsSoALaterCompactionCanDeleteTheInputs() throws IOException{
        IntegerKeyCodec codec = new IntegerKeyCodec();
        FullLoadMergeStrategy<Integer> realMerge = new FullLoadMergeStrategy<>(codec);
        AtomicBoolean failNextMerge = new AtomicBoolean(true);
        MergeStrategy<Integer> failsOnce = (segments, snapshot, outputs, sampleEvery) -> {
            if(failNextMerge.getAndSet(false)) throw new IOException("injected: the first merge fails");
            return realMerge.merge(segments, snapshot, outputs, sampleEvery);
        };

        try(LSMTree<Integer> tree = new LSMTree<>(codec, dir, SAMPLE_EVERY, NEVER_FLUSHES, new FullCompactStrategy<>(1000), failsOnce)){
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();
            List<Segment<Integer>> inputs = tree.segments();

            assertThrows(IOException.class, () -> tree.sharedCompactionLogic(inputs));
            for(Segment<Integer> input : inputs){
                assertEquals(0, input.getRefCount(), () -> "a failed compaction must release its pin on " + input.getDataFile().getName());
            }

            tree.sharedCompactionLogic(tree.segments());
            assertEquals(1, tree.segmentCount(), "precondition: the retry merges both inputs into one segment");

            for(Segment<Integer> input : inputs){
                for(File file : List.of(input.getDataFile(), input.getIndexFile(), input.getBloomFilterFile())){
                    assertFalse(file.exists(), () -> "a retired input must be deleted in-session, not held by a leaked pin: " + file.getName());
                }
            }
            assertArrayEquals(value(1), tree.get(1));
            assertArrayEquals(value(2), tree.get(2));
        }
    }

    @Test
    void failedCompactionRethrowsTheOriginalEvenWhenItsCleanupFails() throws IOException{
        IOException injected = new IOException("injected: merge failed");
        MergeStrategy<Integer> failingMerge = (segments, snapshot, outputs, sampleEvery) -> {
            // An output whose data file is a non-empty directory: deleting it throws, so the cleanup fails too.
            Files.createDirectories(outputs.get().dataFile().toPath().resolve("blocker"));
            throw injected;
        };

        try(LSMTree<Integer> tree = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, NEVER_FLUSHES, new FullCompactStrategy<>(1000), failingMerge)){
            tree.put(1, value(1));
            tree.flush();
            tree.put(2, value(2));
            tree.flush();

            IOException thrown = assertThrows(IOException.class, () -> tree.sharedCompactionLogic(tree.segments()));

            assertSame(injected, thrown, "cleanup must not replace or wrap the original failure");
            assertEquals(1, thrown.getSuppressed().length, "the cleanup's own failure must be attached, not lost");
            assertEquals(2, tree.segmentCount(), "the inputs stay live");
        }
    }
}
