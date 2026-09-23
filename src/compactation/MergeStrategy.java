package compactation;

import core.Segment;
import core.SegmentFiles;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

public interface MergeStrategy<K extends Comparable<K>> {
    List<Segment<K>> merge(List<Segment<K>> segments, Supplier<SegmentFiles> segmentFilesSupplier, int sampleEvery) throws IOException;
}
