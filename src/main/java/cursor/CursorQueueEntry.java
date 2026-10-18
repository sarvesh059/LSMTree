package cursor;

import rbt.Entry;
import core.Value;

public record CursorQueueEntry<K>(Entry<K, Value> entry, long eventId, int index) {
}
