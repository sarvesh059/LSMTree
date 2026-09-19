package cursor;

import RBT.Entry;
import core.Pair;
import core.Value;

import java.io.IOException;
import java.util.List;
import java.util.PriorityQueue;

public class MergeCursor<K extends Comparable<K>> implements EntrySource<K>{
    private final PriorityQueue<Pair<Entry<K, Value>,  Integer>> pq = new PriorityQueue<>((a, b) -> {
        if(a.getFirst().getKey().compareTo(b.getFirst().getKey()) == 0) return b.getSecond().compareTo(a.getSecond());
        return a.getFirst().getKey().compareTo(b.getFirst().getKey());
    });
    private final EntrySource<K>[] cursors;

    public MergeCursor(List<EntrySource<K>> sources) throws IOException {
        this.cursors = sources.toArray(new EntrySource[0]);
        for (int i = 0; i < cursors.length; i++) {
            if (cursors[i].hasNext()) pq.add(new Pair<>(cursors[i].next(), i));
        }
    }


    @Override
    public boolean hasNext() throws IOException {
        while(!pq.isEmpty() && pq.peek().getFirst().getValue().isTombstone()){
            Pair<Entry<K, Value>,  Integer> tombstoneEntry = pq.poll();
            moveCursor(tombstoneEntry.getSecond());

            deduplicate(tombstoneEntry.getFirst().getKey());
        }
        return !pq.isEmpty();
    }

    @Override
    public Entry<K, Value> next() throws IOException {
        Pair<Entry<K, Value>,  Integer> minEntry = pq.poll();
        moveCursor(minEntry.getSecond());

        deduplicate(minEntry.getFirst().getKey());

        return minEntry.getFirst();
    }

    private void deduplicate(K key) throws IOException {
        while(!pq.isEmpty() && pq.peek().getFirst().getKey().equals(key)){
            Pair<Entry<K, Value>,  Integer> duplicateEntry = pq.poll();
            moveCursor(duplicateEntry.getSecond());
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
}
