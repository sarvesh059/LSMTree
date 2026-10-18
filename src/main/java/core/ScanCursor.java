package core;

import java.io.Closeable;
import java.io.IOException;

public interface ScanCursor<K, V> extends Closeable {
    boolean hasNext() throws IOException;

    Entry<K, V> next() throws IOException;
}
