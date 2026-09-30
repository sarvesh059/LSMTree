package compaction.TombStoneRetentionPolicyImpl;

import RBT.Entry;
import cursor.TombStoneRetentionPolicy;
import core.Segment;
import core.Value;
import core.key.KeyCodec;

import java.util.List;

public class CoverageBasedTombStoneRetentionPolicy<K> implements TombStoneRetentionPolicy<K> {
    final List<Segment<K>> segments;
    final KeyCodec<K> codec;

    public CoverageBasedTombStoneRetentionPolicy(List<Segment<K>> segments, KeyCodec<K> codec) {
        this.segments = segments;
        this.codec = codec;
    }

    @Override
    public boolean shouldRetain(Entry<K, Value> entry) {
        byte[] encodedKey = this.codec.encodeKey(entry.getKey());
        return segments.stream().anyMatch(segment -> !segment.keyDefinitelyNotInSegment(
                encodedKey,
                this.codec
        ));
    }
}
