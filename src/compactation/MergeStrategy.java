package compactation;

import core.Segment;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface MergeStrategy<K extends Comparable<K>> {
    Segment<K> merge(List<Segment<K>> segments, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery) throws IOException;
}
