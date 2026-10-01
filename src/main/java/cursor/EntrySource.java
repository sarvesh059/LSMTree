package cursor;

import RBT.Entry;
import core.Value;

import java.io.Closeable;
import java.io.IOException;

public interface EntrySource<K> extends Closeable {
    boolean hasNext() throws IOException;
    Entry<K, Value> next() throws IOException;
}
