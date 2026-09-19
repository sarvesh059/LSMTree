package compactation.MergeStrategyImpl;

import RBT.Entry;
import SSTable.EntrySource;
import SSTable.SSTable;
import compactation.MergeStrategy;
import core.Segment;
import core.Value;
import core.key.KeyCodec;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.PriorityQueue;

public class StreamingMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;

    record Pair<A, B>(A first, B second) {}

    public StreamingMergeStrategy(KeyCodec<K> codec) {
        this.codec = codec;
    }

    @Override
    public Segment<K> merge(List<Segment<K>> segments, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery) throws IOException {
        int totalSegments = segments.size();
        int approxEntires = segments.stream().mapToInt(Segment::getEntryCount).sum();
        EntrySource<K>[] cursors = new EntrySource[totalSegments];
        SSTable<K> table = new SSTable<K>(this.codec);

        PriorityQueue<Pair<Entry<K, Value>,  Integer>> pq = new PriorityQueue<>((a,b) -> {
            if(a.first.getKey().compareTo(b.first.getKey()) == 0) return b.second.compareTo(a.second);
            return a.first.getKey().compareTo(b.first.getKey());
        });

        for(int i=0;i<totalSegments;i++){
            cursors[i] = table.openCursor(segments.get(i).getDataFile());
            if(cursors[i].hasNext()) pq.add(new Pair<>(cursors[i].next(), i));
        }

        EntrySource<K> source = new EntrySource<K>() {
            @Override
            public boolean hasNext() {
                try{
                    while(!pq.isEmpty() && pq.peek().first.getValue().isTombstone()){
                        Pair<Entry<K, Value>,  Integer> tombstoneEntry = pq.poll();
                        moveCursor(tombstoneEntry.second);

                        deduplicate(tombstoneEntry.first.getKey());
                    }
                } catch (IOException e){
                    return false;
                }
                return !pq.isEmpty();
            }

            @Override
            public Entry<K, Value> next() throws IOException {
                Pair<Entry<K, Value>,  Integer> minEntry = pq.poll();
                moveCursor(minEntry.second);

                deduplicate(minEntry.first.getKey());

                return minEntry.first;
            }

            private void deduplicate(K key) throws IOException {
                while(!pq.isEmpty() && pq.peek().first.getKey().equals(key)){
                    Pair<Entry<K, Value>,  Integer> duplicateEntry = pq.poll();
                    moveCursor(duplicateEntry.second);
                }
            }

            private void moveCursor(int index) throws IOException {
                if(cursors[index].hasNext()) pq.add(new Pair<>(cursors[index].next(), index));
            }

            @Override
            public void close() throws IOException {
                for (EntrySource<K> cursor : cursors) {
                    cursor.close();
                }
            }
        };

        try {
            return table.write(source, dataFile, indexFile, bloomFilterFile, sampleEvery, approxEntires);
        } finally {
            source.close();
        }
    }
}
