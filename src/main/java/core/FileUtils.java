package core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
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

    public static void cleanUpFiles(List<Path> files) throws IOException{
        IOException exception = null;
        for (Path filePath : files) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                if (exception == null) {
                    exception = e;
                } else {
                    exception.addSuppressed(e);
                }
            }
        }

        if (exception != null) {
            throw exception;
        }
    }
}
