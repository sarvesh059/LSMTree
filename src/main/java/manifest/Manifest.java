package manifest;

import constants.FileConstants;
import core.FileUtils;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Manifest {
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[;\\n\\r]");

    public static void append(String segmentId, File manifestFile) throws IOException {
        if (FORBIDDEN_CHARS.matcher(segmentId).find()){
            throw new IllegalArgumentException("segmentId must not contain ';' or a line separator: "+ segmentId);
        }
        try(RandomAccessFile outputStream = new RandomAccessFile(manifestFile, "rw")){
            outputStream.seek(outputStream.length());
            outputStream.write((segmentId+";\n").getBytes(StandardCharsets.UTF_8));

            outputStream.getFD().sync();
        }
    }

    public static List<String> readAll(File manifestFile) throws IOException {
        List<String> segmentIds = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(manifestFile), StandardCharsets.UTF_8))){
            // Manifest header
            String line = reader.readLine();
            if(line == null || !line.equals(FileConstants.MANIFEST_FILE_START_LINE)) throw new IOException("Manifest file not recognised: "+ manifestFile.getAbsolutePath());

            line = reader.readLine();
            while (line != null) {
                String nextLine = reader.readLine();

                if (line.endsWith(";")) {
                    segmentIds.add(line.substring(0, line.length() - 1));
                } else if (nextLine != null) {
                    throw new IOException("Invalid segment record in manifest: " + line);
                }

                line = nextLine;
            }
        }

        return segmentIds;
    }

    public static void rewrite(List<String> segmentIds, File manifestFile) throws IOException {
        File tmpFile = new File(manifestFile.getParent(), manifestFile.getName() + ".tmp");
        try(FileChannel channel = FileChannel.open(tmpFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            BufferedWriter out = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))){
            out.write(FileConstants.MANIFEST_FILE_START_LINE);
            out.newLine();
            for(String segmentId: segmentIds){
                if (FORBIDDEN_CHARS.matcher(segmentId).find()){
                    throw new IllegalArgumentException("segmentId must not contain ';' or a line separator: "+ segmentId);
                }
                out.write(segmentId + ";");
                out.newLine();
            }
            out.flush();
            channel.force(true);
        }
        Files.move(tmpFile.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        File parent = manifestFile.getParentFile();
        if (parent != null) {
            FileUtils.fsyncDirectory(parent.toPath());
        }
    }

    public static void initialiseManifestFile(File manifestFile) throws IOException{
        if(manifestFile.exists()){
            try(RandomAccessFile outputStream = new RandomAccessFile(manifestFile, "r")) {
                String line = outputStream.readLine();
                if(line == null || !line.equals(FileConstants.MANIFEST_FILE_START_LINE)) throw new IOException("Corrupted Manifest file found: "+manifestFile.getAbsolutePath());
            }
        }else{
            rewrite(List.of(), manifestFile);
        }
    }

}
