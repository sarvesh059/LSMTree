package core.key;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public interface KeyCodec<K> {
    byte[] encode(K key, DataOutput out) throws IOException;
    K decode(DataInput in) throws IOException;
    int compareEncoded(byte[] a, byte[] b);
    byte[] readRawEncoded(DataInput in) throws IOException;
    K decodeKey(byte[] encodedKey);
    byte[] encodeKey(K key);
}
