package WAL;

import core.Value;
import core.key.KeyCodec;
import memTable.MemTable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class WAL<K extends Comparable<K>> implements Closeable {
    private final KeyCodec<K> keyCodec;
    private final File walFile;
    private final RandomAccessFile fileStream;
    private long latestEventId = -1;
    private final ByteArrayOutputStream byteArrayOutputStream;
    private static final byte[] magic = "WAL1".getBytes(StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(WAL.class.getName());
    private final int HEADER_SIZE = magic.length + 8 + 4 + 4;

    public WAL(KeyCodec<K> keyCodec, File walFile) throws IOException{
        this.keyCodec = keyCodec;
        this.walFile = walFile;
        this.fileStream = new RandomAccessFile(walFile, "rw");
        this.fileStream.seek(this.fileStream.length());
        this.byteArrayOutputStream = new ByteArrayOutputStream();
    }

    public void append(K key, Value value) throws IOException {
        DataOutputStream dataOutputStream = new DataOutputStream(this.byteArrayOutputStream);
        latestEventId = Math.max(latestEventId, value.getId());
        this.keyCodec.encode(key, dataOutputStream);
        value.writeTo(dataOutputStream);
        int length = byteArrayOutputStream.size();
        byte[] data = byteArrayOutputStream.toByteArray();
        int checksum = getCRC32CheckSum(data);
        // WAL record format [magic][tag][checksum][len][payload]
        this.fileStream.write(magic); // magic
        this.fileStream.writeLong(latestEventId); // tag
        this.fileStream.writeInt(checksum); // checkSum
        this.fileStream.writeInt(length); // len
        this.fileStream.write(data); // data
        byteArrayOutputStream.reset();
    }

    public ReplayResult replay(MemTable<K> targetMemTable) throws IOException{
        int recordsRecovered = 0;
        long length;
        try(RandomAccessFile in = new RandomAccessFile(this.walFile, "r")){
            long lastTag = Long.MIN_VALUE;
            length = in.length();
            while (in.getFilePointer() < length) {
                long offset = in.getFilePointer();
                if(length - offset < HEADER_SIZE){
                    return stopAndLog(WALReplayStopReason.TRUNCATED, offset, recordsRecovered);
                }
                byte[] recordMagicTag = new byte[4];
                in.readFully(recordMagicTag);
                if(!Arrays.equals(magic, recordMagicTag)){
                    return stopAndLog(WALReplayStopReason.BAD_MAGIC, offset, recordsRecovered);
                }
                long tag = in.readLong();
                if(tag < lastTag){
                    return stopAndLog(WALReplayStopReason.STALE_RECORD, offset, recordsRecovered);
                }
                lastTag = tag;
                int readChecksum = in.readInt();
                int payloadLength = in.readInt();

                if(payloadLength <= 0){
                    return stopAndLog(WALReplayStopReason.INVALID_LENGTH, offset, recordsRecovered);
                }

                if(payloadLength > length-in.getFilePointer()){
                    return stopAndLog(WALReplayStopReason.TRUNCATED, offset, recordsRecovered);
                }

                byte[] payload = new byte[payloadLength];
                in.readFully(payload);

                int computedChecksum = getCRC32CheckSum(payload);
                if(readChecksum != computedChecksum){
                    return stopAndLog(WALReplayStopReason.CHECKSUM_MISMATCH, offset, recordsRecovered);
                }
                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream( payload);
                DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);

                K key = this.keyCodec.decode(dataInputStream);
                Value value = Value.readFrom(dataInputStream);
                latestEventId = Math.max(latestEventId, value.getId());
                targetMemTable.put(key, value);
                recordsRecovered++;
            }
        }

        return new ReplayResult(recordsRecovered, WALReplayStopReason.CLEAN_EOF, length);
    }

    public long getLatestEventId(){
        return this.latestEventId;
    }

    public void fsync() throws IOException {
        this.fileStream.getFD().sync();
    }

    public void reset() throws IOException {
        this.fileStream.setLength(0);
        this.fileStream.seek(0);
        fsync();
    }

    private ReplayResult stopAndLog(WALReplayStopReason reason, long offset, int recordsRecovered){
        LOG.log(reason.logLevel(),
                () -> "WAL replay stopped at offset " + offset + " of " + walFile
                        + ": " + reason + ". " + recordsRecovered
                        + " records recovered. Everything after this offset is discarded.");
        return new ReplayResult(recordsRecovered, reason, offset);
    }

    @Override
    public void close() throws IOException{
        this.fileStream.close();
    }

    private static int getCRC32CheckSum(byte[] data){
        Checksum crc32 = new CRC32();
        crc32.update(data);
        return (int) crc32.getValue();
    }
}
