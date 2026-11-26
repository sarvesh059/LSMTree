package constants;

import java.util.Arrays;

public enum SegmentAction {
    ADDED("ADDED"),
    REMOVED("REMOVED");

    private final String action;

    SegmentAction(String action) {
        this.action = action;
    }

    public String getAction() {
        return action;
    }

    public static SegmentAction fromAction(String action) {
        return Arrays.stream(values())
                .filter(a -> a.action.equals(action))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown segment action: " + action
                        ));
    }
}
