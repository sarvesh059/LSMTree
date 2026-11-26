package manifest;

import constants.FileConstants;
import constants.SegmentAction;
import core.FileUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

public class Manifest {
    private static final byte[] magic = "MANIFEST".getBytes(StandardCharsets.UTF_8);
    private static final int MAGIC_OFFSET = 0;
    private static final int CRC_OFFSET = magic.length;
    private static final int SEQUENCE_OFFSET=CRC_OFFSET+4;
    private static final int LENGTH_OFFSET = SEQUENCE_OFFSET+8;
    private static final int HEADER_SIZE = LENGTH_OFFSET+4;
    private static final int CRC_COVERED_FROM = SEQUENCE_OFFSET;
    private final File manifestFile;
    private final AtomicLong sequenceNumber;

    private Manifest(File manifestFile, long sequenceNumber){
        this.manifestFile = manifestFile;
        this.sequenceNumber = new AtomicLong(sequenceNumber);
    }

    public void append(String segmentId) throws IOException {
        SegmentChangeLog segmentChangeLog = new SegmentChangeLog(segmentId, SegmentAction.ADDED);
        ManifestPayload manifestPayload = new ManifestPayload(List.of(segmentChangeLog));
        append(manifestPayload);
    }

    public void append(ManifestPayload manifestPayload) throws IOException{
        try(RandomAccessFile outputStream = new RandomAccessFile(manifestFile, "rw")){
            outputStream.seek(outputStream.length());

            byte[] record = encodeRecord(manifestPayload);
            outputStream.write(record);
            outputStream.getFD().sync();
        }
    }

    public List<String> readAll() throws IOException {
        List<ManifestRecord> records = readAllRecords(this.manifestFile);
        Set<String> segmentSet = new LinkedHashSet<>();
        for(ManifestRecord record: records){
            ManifestPayload payload = record.manifestPayload();
            for(SegmentChangeLog segmentChangeLog: payload.getSegmentChangeLogs()){
                if(segmentChangeLog.segmentAction().equals(SegmentAction.ADDED)){
                    segmentSet.add(segmentChangeLog.segmentId());
                }else if(segmentChangeLog.segmentAction().equals(SegmentAction.REMOVED)){
                    segmentSet.remove(segmentChangeLog.segmentId());
                }
            }
        }
        return segmentSet.stream().toList();
    }

    private static List<ManifestRecord> readAllRecords(File manifestFile) throws IOException{
        List<ManifestRecord> records = new ArrayList<>();
        long fileSize = Files.size(manifestFile.toPath());
        long position = 0;
        long lastSequenceNumber = -1;
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(manifestFile.toPath()), 64*1024))){
            byte[] expectedHeader = FileConstants.MANIFEST_FILE_START_LINE.getBytes(StandardCharsets.UTF_8);
            byte[] manifestHeader = new byte[expectedHeader.length];
            in.readFully(manifestHeader);

            if(!Arrays.equals(expectedHeader, manifestHeader)){
                throw new IOException("Manifest file not recognised: "+ manifestFile.getAbsolutePath());
            }
            position += expectedHeader.length;

            while(position < fileSize){
                if(position+HEADER_SIZE > fileSize){
                    throw new IOException( "Truncated manifest record header located at position: "+position + ". File: "+manifestFile.getAbsolutePath());
                }
                byte[] header = new byte[HEADER_SIZE];
                in.readFully(header);
                position += HEADER_SIZE;

                byte[] recordMagicTag = new byte[magic.length];
                ByteBuffer.wrap(header).get(MAGIC_OFFSET, recordMagicTag);

                if(!Arrays.equals(magic, recordMagicTag)){
                    throw new IOException("Manifest record not recognised at position: " + (position-HEADER_SIZE) + ". File: "+manifestFile.getAbsolutePath());
                }

                int readChecksum = ByteBuffer.wrap(header).getInt(CRC_OFFSET);
                long sequenceNumber = ByteBuffer.wrap(header).getLong(SEQUENCE_OFFSET);

                int payloadLength = ByteBuffer.wrap(header).getInt(LENGTH_OFFSET);

                if(payloadLength < 0){
                    throw  new IOException("Invalid payload length found at position: "+ (position-HEADER_SIZE) + ". File: "+manifestFile.getAbsolutePath());
                }

                if(position + payloadLength > fileSize){
                    throw new IOException( "Truncated manifest record payload located at position: "+(position-HEADER_SIZE) + ". File: "+manifestFile.getAbsolutePath());
                }

                byte[] payload = new byte[payloadLength];
                in.readFully(payload);
                position += payloadLength;

                Checksum crc = newChecksum();
                crc.update(header, CRC_COVERED_FROM, HEADER_SIZE - CRC_COVERED_FROM);
                crc.update(payload, 0, payload.length);
                if(readChecksum != (int) crc.getValue()){
                    throw new IOException("Corrupted record found at position: "+(position-payloadLength-HEADER_SIZE) + ". File: "+manifestFile.getAbsolutePath());
                }

                if(lastSequenceNumber != -1 && sequenceNumber != lastSequenceNumber+1) throw new IOException("Missing Sequence numbers in manifest between "+lastSequenceNumber+ " and "+sequenceNumber + " at position: "+ (position-payloadLength-HEADER_SIZE) + ". File: "+manifestFile.getAbsolutePath());
                lastSequenceNumber = sequenceNumber;

                ManifestPayload  manifestPayload = ManifestPayload.decode(payload);
                records.add(new ManifestRecord(sequenceNumber, manifestPayload));

            }

        }

        return records;
    }

    public void rewrite(List<String> segmentIds) throws IOException {
        File tmpFile = new File(manifestFile.getParent(), manifestFile.getName() + ".tmp");
        try(FileChannel channel = FileChannel.open(tmpFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)){
            writeFully(channel, FileConstants.MANIFEST_FILE_START_LINE);
            List<SegmentChangeLog> segmentChangeLogList = new ArrayList<>();
            for(String segmentId: segmentIds){
                SegmentChangeLog segmentChangeLog = new SegmentChangeLog(segmentId, SegmentAction.ADDED);
                segmentChangeLogList.add(segmentChangeLog);
            }

            ManifestPayload manifestPayload = new ManifestPayload(segmentChangeLogList);
            byte[] data = encodeRecord(manifestPayload);
            writeFully(channel, data);
            channel.force(true);
        }
        Files.move(tmpFile.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        File parent = manifestFile.getParentFile();
        if (parent != null) {
            FileUtils.fsyncDirectory(parent.toPath());
        }
    }

    public static Manifest initialiseManifestFile(File manifestFile) throws IOException{
        if(manifestFile.exists()){
            byte[] expectedHeader = FileConstants.MANIFEST_FILE_START_LINE.getBytes(StandardCharsets.UTF_8);
            if(Files.size(manifestFile.toPath()) < expectedHeader.length){
                throw new IOException("Truncated Manifest header found: "+ manifestFile.getAbsolutePath());
            }
            List<ManifestRecord> manifestRecords = Manifest.readAllRecords(manifestFile);
            long sequenceId = manifestRecords.stream().map(ManifestRecord::sequenceNumber).max(Long::compareTo).orElse(0L);
            return new Manifest(manifestFile, sequenceId);
        }else{
            Manifest manifest = new Manifest(manifestFile, 0);
            manifest.rewrite(List.of());
            return manifest;
        }
    }

    public boolean fileExists(){
        return this.manifestFile.exists();
    }

    public Path getFilePath(){
        return this.manifestFile.toPath();
    }

    private byte[] encodeRecord(ManifestPayload manifestPayload) throws IOException{
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(HEADER_SIZE + 32);
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        byte[] data = manifestPayload.encode();
        dataOutputStream.write(magic); //magic
        dataOutputStream.writeInt(0); //CRC placeholder
        dataOutputStream.writeLong(sequenceNumber.incrementAndGet()); // sequence number
        dataOutputStream.writeInt(data.length); //length
        dataOutputStream.write(data); // data

        byte[] frame = byteArrayOutputStream.toByteArray();
        ByteBuffer.wrap(frame).putInt(CRC_OFFSET, getCRC32CCheckSum(frame, CRC_COVERED_FROM, frame.length - CRC_COVERED_FROM)); // CRC of len + data
        return frame;
    }

    private static Checksum newChecksum(){
        return new CRC32C();
    }

    private int getCRC32CCheckSum(byte[] data, int offset, int length){
        Checksum crc32 = newChecksum();
        crc32.update(data, offset, length);
        return (int) crc32.getValue();
    }

    private static void writeFully(FileChannel channel, byte[] data)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void writeFully(FileChannel channel, String data)
            throws IOException {
        writeFully(channel, data.getBytes(StandardCharsets.UTF_8));
    }

}
