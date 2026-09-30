package cursor;

import RBT.Entry;
import core.Value;

public interface TombStoneRetentionPolicy <K> {
    boolean shouldRetain(Entry<K, Value> entry);
}
