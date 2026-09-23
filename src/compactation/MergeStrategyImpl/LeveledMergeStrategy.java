package compactation.MergeStrategyImpl;

import compactation.MergeStrategy;
import core.Segment;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class LeveledMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    @Override
    public List<Segment<K>> merge(List<Segment<K>> segments, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery) throws IOException {
        return List.of();
    }
}
