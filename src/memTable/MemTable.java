package memTable;

import RBT.*;
import core.Value;

import java.util.List;

public class MemTable<K extends Comparable<K>> {
    private final RBT<K, Value> tree;
    private int sizeInBytes;
    private final int threshold;

    public MemTable(int threshold){
        this.tree = new RBT<K, Value>();
        this.sizeInBytes = 0;
        this.threshold = threshold;
    }

    public void put(K key, Value value){
        Value oldVal = get(key);
        int oldSize = oldVal != null ? oldVal.getSizeInBytes() : 0;
        int newSize = value.getSizeInBytes();

        this.sizeInBytes += (newSize - oldSize);
        this.tree.insert(key, value);
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
        return this.sizeInBytes >= this.threshold;
    }

    public int getSizeInBytes(){
        return this.sizeInBytes;
    }
}
