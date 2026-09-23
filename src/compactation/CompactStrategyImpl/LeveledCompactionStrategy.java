package compactation.CompactStrategyImpl;

import compactation.CompactionStrategy;
import core.Segment;

import java.util.*;

public class LeveledCompactionStrategy<K extends Comparable<K>> implements CompactionStrategy<K> {

    private final int L0THRESHOLD = 4;
    private final int LEVELSIZEMULTIPLIER = 10;
    private final long BASESIZEINBYTES = 1024*1024;
    private final Map<Integer, byte[]> compactPointer = new HashMap<>();
    @Override
    public boolean shouldCompact(List<Segment<K>> segments) {
        Map<Integer,List<Segment<K>>> levels = new TreeMap<>();

        for(Segment<K> segment: segments){
            levels.computeIfAbsent(segment.getLevel(), k -> new ArrayList<>()).add(segment);
        }

        List<List<Segment<K>>> resultingSegments = new ArrayList<>();
        for(Map.Entry<Integer,List<Segment<K>>> entry: levels.entrySet()){
            if(entry.getKey() == 0){
                if(entry.getValue().size() >= L0THRESHOLD){
                    return true;
                }
            }
            else{
                int level = entry.getKey();
                List<Segment<K>> levelSegments = entry.getValue();
                long totalSize = levelSegments.stream()
                        .mapToLong(levelSegment -> levelSegment.getDataFile().length())
                        .sum();
                if(totalSize >= Math.pow(LEVELSIZEMULTIPLIER, level-1)* BASESIZEINBYTES){
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<Segment<K>> select(List<Segment<K>> segments) {

        Map<Integer,List<Segment<K>>> levels = new TreeMap<>();

        for(Segment<K> segment: segments){
            levels.computeIfAbsent(segment.getLevel(), k -> new ArrayList<>()).add(segment);
        }

        for(Map.Entry<Integer,List<Segment<K>>> entry: levels.entrySet()){
            if(entry.getKey() == 0){
                if(entry.getValue().size() >= L0THRESHOLD){
                    List<Segment<K>> levelSegments = entry.getValue();
                    var minMaxKeyRef = new Object() {
                        byte[] maxKey = levelSegments.getFirst().getMaxKey();
                        byte[] minKey = levelSegments.getFirst().getMinKey();
                    };
                    for(Segment<K> segment: levelSegments){
                        if(Arrays.compareUnsigned(segment.getMinKey(), minMaxKeyRef.minKey) < 0) minMaxKeyRef.minKey = segment.getMinKey();
                        if(Arrays.compareUnsigned(segment.getMaxKey(), minMaxKeyRef.maxKey) > 0) minMaxKeyRef.maxKey = segment.getMaxKey();
                    }

                    List<Segment<K>> nextLevelSegments = levels.get(1);
                    if(nextLevelSegments == null || nextLevelSegments.isEmpty()) return levelSegments;

                    List<Segment<K>> overLappingSegments = nextLevelSegments.stream()
                            .filter(kSegment -> !(Arrays.compareUnsigned(kSegment.getMinKey(), minMaxKeyRef.maxKey) > 0 || Arrays.compareUnsigned(kSegment.getMaxKey(), minMaxKeyRef.minKey) < 0))
                            .toList();
                    List<Segment<K>> selectedSegments = new ArrayList<>(levelSegments);
                    selectedSegments.addAll(overLappingSegments);
                    return selectedSegments;
                }
            }
            else{
                int level = entry.getKey();
                List<Segment<K>> levelSegments = entry.getValue().stream()
                        .sorted((s1, s2) -> Arrays.compareUnsigned(s1.getMinKey(), s2.getMinKey()))
                        .toList();
                long totalSize = levelSegments.stream()
                        .mapToLong(levelSegment -> levelSegment.getDataFile().length())
                        .sum();
                if(totalSize >= Math.pow(LEVELSIZEMULTIPLIER, level-1)* BASESIZEINBYTES){
                    byte[] lastCompactedLevelKey = compactPointer.get(level);
                    Segment<K> nextSegmentToBeCompacted = levelSegments.stream()
                            .filter(kSegment -> Arrays.compareUnsigned(kSegment.getMinKey(), lastCompactedLevelKey)> 0)
                            .findFirst()
                            .orElse(levelSegments.getFirst());

                    this.compactPointer.put(level, nextSegmentToBeCompacted.getMaxKey());
                    List<Segment<K>> nextLevelSegments = levels.get(level+1);
                    if(nextLevelSegments == null || nextLevelSegments.isEmpty()) return List.of(nextSegmentToBeCompacted);

                    List<Segment<K>> overLappingSegments = nextLevelSegments.stream()
                            .filter(kSegment -> !(Arrays.compareUnsigned(kSegment.getMinKey(), nextSegmentToBeCompacted.getMaxKey()) > 0 || Arrays.compareUnsigned(kSegment.getMaxKey(), nextSegmentToBeCompacted.getMinKey()) < 0))
                            .toList();
                    List<Segment<K>> selectedSegments = new ArrayList<>();
                    selectedSegments.add(nextSegmentToBeCompacted);
                    selectedSegments.addAll(overLappingSegments);
                    return selectedSegments;
                }
            }
        }

        return List.of();
    }
}
