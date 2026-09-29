package compaction.MergeStrategyImpl;

import RBT.Entry;
import SSTable.SSTable;
import core.Segment;
import core.SegmentFiles;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class StreamingMergeStrategyTest {

    @Test
    void cursorsAreClosedWhenAnotherSegmentFailsMidMerge(@TempDir Path dir) throws IOException {
        SSTable<Integer> table = new SSTable<>(new IntegerKeyCodec());

        var dataGood = dir.resolve("good.data").toFile();
        var idxGood = dir.resolve("good.idx").toFile();
        var bloomGood = dir.resolve("good.filter").toFile();
        Segment<Integer> goodSegment = table.write(List.of(new Entry<Integer,Value>(1, Value.of(new byte[]{1})), new Entry<Integer,Value>(2, Value.of(new byte[]{2}))), dataGood, idxGood, bloomGood, 5, 0);

        var dataBad = dir.resolve("bad.data").toFile();
        var idxBad = dir.resolve("bad.idx").toFile();
        var bloomBad = dir.resolve("bad.filter").toFile();
        Segment<Integer> badSegment = table.write(List.of(new Entry<Integer,Value>(10, Value.of(new byte[]{10})), new Entry<Integer,Value>(11, Value.of(new byte[]{11}))), dataBad, idxBad, bloomBad, 5, 0);
        try (RandomAccessFile raf = new RandomAccessFile(dataBad, "rw")) {
            raf.setLength(raf.length() - 3);
        }

        StreamingMergeStrategy<Integer> merger = new StreamingMergeStrategy<>(new IntegerKeyCodec());
        var outData = dir.resolve("out.data").toFile();
        var outIdx = dir.resolve("out.idx").toFile();
        var outBloom = dir.resolve("out.filter").toFile();
        Supplier<SegmentFiles> fileFactory = () -> new SegmentFiles(outData, outIdx, outBloom);

        assertThrows(IOException.class,
                () -> merger.merge(List.of(goodSegment, badSegment), List.of(goodSegment, badSegment), fileFactory, 5),
                "a truncated entry in one segment should surface as an IOException, not be silently absorbed");

        assertTrue(dataGood.delete(), "the good segment's file handle should have been released despite the other segment's failure");
    }
}
