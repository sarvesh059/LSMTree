package manifest;

import constants.SegmentAction;

public record SegmentChangeLog(String segmentId, SegmentAction segmentAction) {
}
