package compaction.CompactStrategyImpl;

import compaction.CompactionStrategy;
import core.Segment;

import java.util.*;

public class SizeTieredCompactStrategy<K extends Comparable<K>> implements CompactionStrategy<K> {
    private final int minSegmentPerTier;
    private final double bucketLow;
    private final double bucketHigh;

    public SizeTieredCompactStrategy(int minSegmentPerTier, double bucketLow, double bucketHigh) {
        this.minSegmentPerTier = minSegmentPerTier;
        this.bucketLow = bucketLow;
        this.bucketHigh = bucketHigh;
    }

    @Override
    public boolean shouldCompact(List<Segment<K>> segments) {
        List<List<Segment<K>>> segmentBuckets = bucketSegments(segments);

        return !segmentBuckets.isEmpty();
    }

    @Override
    public List<Segment<K>> select(List<Segment<K>> segments) {
        List<List<Segment<K>>> segmentBuckets = bucketSegments(segments);
        return segmentBuckets.isEmpty()? null : segmentBuckets.getFirst();
    }

    private List<List<Segment<K>>> bucketSegments(List<Segment<K>> segments){
        List<List<Segment<K>>> segmentBuckets = new ArrayList<>();

        List<Segment<K>> sorted = segments.stream()
                .sorted(Comparator.comparingLong(Segment::getSize))
                .toList();
        double currentLow = 0;
        double currentHigh = 0;
        Map<Double,List<Segment<K>>> buckets = new LinkedHashMap<>();
        for(Segment<K> segment: sorted){
            long size = segment.getSize();
            if(size > currentHigh){
                currentLow = this.bucketLow*size;
                currentHigh = this.bucketHigh*size;
            }
            buckets.computeIfAbsent(currentLow,k -> new ArrayList<>()).add(segment);
        }

        for(Map.Entry<Double, List<Segment<K>>> entry: buckets.entrySet()){
            if(entry.getValue().size() >= this.minSegmentPerTier) segmentBuckets.add(entry.getValue());
        }

        return segmentBuckets;
    }
}
