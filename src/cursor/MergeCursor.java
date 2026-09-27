package cursor;

import RBT.Entry;
import core.Value;

import java.io.IOException;
import java.util.List;
import java.util.PriorityQueue;

public class MergeCursor<K extends Comparable<K>> implements EntrySource<K>{
    private final PriorityQueue<CursorQueueEntry<K>> pq = new PriorityQueue<>((a,b) -> {
        if(a.entry().getKey().equals(b.entry().getKey())){
            return Long.compare(b.eventId(), a.eventId());
        }
        return a.entry().getKey().compareTo(b.entry().getKey());
    });

    private final EntrySource<K>[] cursors;
    private final Runnable onClose;

    public MergeCursor(List<EntrySource<K>> sources, Runnable onClose) throws IOException {
        this.cursors = sources.toArray(new EntrySource[0]);
        this.onClose = onClose;
        initialiseCursors();
    }

    public MergeCursor(List<EntrySource<K>> sources) throws IOException {
        this.cursors = sources.toArray(new EntrySource[0]);
        this.onClose = () -> {};
        initialiseCursors();
    }

    private void initialiseCursors() throws IOException {
        for (int i = 0; i < cursors.length; i++) {
            if (cursors[i].hasNext()){
                Entry<K, Value> entry = cursors[i].next();
                pq.add(new CursorQueueEntry<>(entry, entry.getValue().getId(), i));
            }
        }
    }



    @Override
    public boolean hasNext() throws IOException {
        while(!pq.isEmpty() && pq.peek().entry().getValue().isTombstone()){
            CursorQueueEntry<K> tombstoneEntry = pq.poll();
            moveCursor(tombstoneEntry.index());

            deduplicate(tombstoneEntry.entry().getKey());
        }
        return !pq.isEmpty();
    }

    @Override
    public Entry<K, Value> next() throws IOException {
        CursorQueueEntry<K> minEntry = pq.poll();
        moveCursor(minEntry.index());

        deduplicate(minEntry.entry().getKey());

        return minEntry.entry();
    }

    private void deduplicate(K key) throws IOException {
        while(!pq.isEmpty() && pq.peek().entry().getKey().equals(key)){
            CursorQueueEntry<K> duplicateEntry = pq.poll();
            moveCursor(duplicateEntry.index());
        }
    }

    private void moveCursor(int index) throws IOException {
        if (cursors[index].hasNext()){
            Entry<K, Value> entry = cursors[index].next();
            pq.add(new CursorQueueEntry<>(entry, entry.getValue().getId(), index));
        }
    }

    @Override
    public void close() throws IOException {
        for (EntrySource<K> cursor : cursors) {
            cursor.close();
        }
        this.onClose.run();
    }
}
