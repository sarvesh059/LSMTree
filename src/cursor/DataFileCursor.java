package cursor;

import RBT.Entry;
import SSTable.SSTable;
import core.Value;
import core.key.KeyCodec;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class DataFileCursor<K> implements EntrySource<K> {
    private final RandomAccessFile input;
    private final KeyCodec<K> keyCodec;
    private final int entryCount;
    private int entriesRead = 0;

    public DataFileCursor(File dataFile, KeyCodec<K> keyCodec) throws IOException {
        this.input = new RandomAccessFile(dataFile, "r");
        this.keyCodec = keyCodec;
        this.entryCount = input.readInt();
    }

    public boolean hasNext() {
        return entriesRead < entryCount;
    }

    public Entry<K, Value> next() throws IOException {
        if (entriesRead >= entryCount) return null;
        K key = this.keyCodec.decode(input);
        Value value = Value.readFrom(input);
        entriesRead++;
        return new Entry<>(key, value);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
