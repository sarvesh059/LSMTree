package compactation.CompactStrategyImpl;

import compactation.CompactionStrategy;
import core.Segment;

import java.util.List;

public class SizeTieredCompactStrategy<K extends Comparable<K>> implements CompactionStrategy<K> {
    @Override
    public boolean shouldCompact(List<Segment<K>> segments) {
        return false;
    }

    @Override
    public List<Segment<K>> select(List<Segment<K>> segments) {
        return List.of();
    }
}
