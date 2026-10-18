package core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class FileUtils {

    private static final boolean DIRECTORY_FSYNC_SUPPORTED;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        DIRECTORY_FSYNC_SUPPORTED = os.contains("linux") || os.contains("mac");
    }

    private FileUtils() {
    }

    public static void fsyncDirectory(Path directory) throws IOException {
        try (FileChannel fileChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            fileChannel.force(true);
        } catch (IOException e) {
            if (DIRECTORY_FSYNC_SUPPORTED) {
                throw e;
            }
        }
    }
}
