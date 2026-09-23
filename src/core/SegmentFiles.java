package core;

import java.io.File;

public record SegmentFiles(File dataFile, File indexFile, File bloomFilterFile) {
}
