package manifest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
            String line;
            while((line = reader.readLine()) != null){
                if(line.endsWith(";")){
                    segmentIds.add(line.substring(0, line.length()-1));
                }
            }
        }

        return segmentIds;
    }

    public static void rewrite(List<String> segmentIds, File manifestFile) throws IOException {
        File tmpFile = new File(manifestFile.getParent(), manifestFile.getName() + ".tmp");
        try(RandomAccessFile out = new RandomAccessFile(tmpFile, "rw")){
            out.setLength(0);
            for(String segmentId: segmentIds){
                if (FORBIDDEN_CHARS.matcher(segmentId).find()){
                    throw new IllegalArgumentException("segmentId must not contain ';' or a line separator: "+ segmentId);
                }
                out.write((segmentId + ";\n").getBytes(StandardCharsets.UTF_8));
            }
            out.getFD().sync();
        }
        Files.move(tmpFile.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

}
