package memTable;

import rbt.*;
import core.Entry;
import core.Value;
import core.key.KeyCodec;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MemTable<K extends Comparable<K>> {
    private final RBT<K, Value> tree;
    private final AtomicInteger sizeInBytes;
    private final int threshold;
    private final KeyCodec<K> keyCodec;

    public MemTable(int threshold, KeyCodec<K> keyCodec){
        this.tree = new RBT<K, Value>();
        this.sizeInBytes = new AtomicInteger(0);
        this.threshold = threshold;
        this.keyCodec = keyCodec;
    }

    public void put(K key, Value newValue){

        Value oldValue = this.tree.insert(key, newValue);
        int delta = oldValue != null
                ? newValue.getSizeInBytes() - oldValue.getSizeInBytes()
                : newValue.getSizeInBytes() + this.keyCodec.encodedSize(key);
        this.sizeInBytes.addAndGet(delta);
    }

    public void delete(K key){
        Value value = Value.tombstone();
        put(key, value);
    }

    public Value get(K key){
        return this.tree.get(key);
    }

    public List<Entry<K, Value>> entries(){
        return this.tree.entries();
    }

    public List<Entry<K, Value>> range(K low, K high){
        return this.tree.rangeEntries(low, high);
    }

    public boolean isFull(){
        return this.sizeInBytes.get() >= this.threshold;
    }

    public int getSizeInBytes(){
        return this.sizeInBytes.get();
    }
}
