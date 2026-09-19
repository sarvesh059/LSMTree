package cursor;

import RBT.Entry;
import core.Value;

public record CursorQueueEntry<K>(Entry<K, Value> entry, long eventId, int index) {
}
