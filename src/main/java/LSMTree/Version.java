package LSMTree;

import core.Segment;
import memTable.MemTable;

import java.util.List;

public record Version<K extends Comparable<K>>(MemTable<K> memTable, List<Segment<K>> segments) {
}
