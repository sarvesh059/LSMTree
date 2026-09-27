package compaction;

import core.Segment;

import java.util.List;

public interface CompactionStrategy<K extends Comparable<K>> {
    boolean shouldCompact(List<Segment<K>> segments);
    List<Segment<K>> select(List<Segment<K>> segments);
}
