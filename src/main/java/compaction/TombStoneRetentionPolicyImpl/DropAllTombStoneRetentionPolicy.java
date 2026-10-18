package compaction.TombStoneRetentionPolicyImpl;

import rbt.Entry;
import cursor.TombStoneRetentionPolicy;
import core.Value;

public class DropAllTombStoneRetentionPolicy<K> implements TombStoneRetentionPolicy<K> {
    @Override
    public boolean shouldRetain(Entry<K, Value> entry) {
        return false;
    }
}
