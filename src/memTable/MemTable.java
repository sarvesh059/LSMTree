package memTable;

import RBT.*;
import core.Value;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MemTable<K extends Comparable<K>> {
    private final RBT<K, Value> tree;
    private final AtomicInteger sizeInBytes;
    private final int threshold;

    public MemTable(int threshold){
        this.tree = new RBT<K, Value>();
        this.sizeInBytes = new AtomicInteger(0);
        this.threshold = threshold;
    }

    public void put(K key, Value newValue){

        Value oldValue = this.tree.insert(key, newValue);
        this.sizeInBytes.addAndGet(oldValue!= null ? newValue.getSizeInBytes() - oldValue.getSizeInBytes(): newValue.getSizeInBytes());
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
