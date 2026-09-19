package SSTable;

import RBT.Entry;
import core.Value;

import java.io.Closeable;
import java.io.IOException;

public interface EntrySource<K> extends Closeable {
    boolean hasNext();
    Entry<K, Value> next() throws IOException;
}
