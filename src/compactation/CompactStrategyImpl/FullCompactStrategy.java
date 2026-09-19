package compactation.CompactStrategyImpl;

import compactation.CompactionStrategy;
import core.Segment;

import java.util.ArrayList;
import java.util.List;

public class FullCompactStrategy<K extends Comparable<K>> implements CompactionStrategy<K> {
    private final int compactionThreshold;

    public FullCompactStrategy(int compactionThreshold){
        this.compactionThreshold = compactionThreshold;
    }

    @Override
    public boolean shouldCompact(List<Segment<K>> segments) {
        return segments.size() >= compactionThreshold;
    }

    @Override
    public List<Segment<K>> select(List<Segment<K>> segments) {
        return new ArrayList<>(segments);
    }
}
