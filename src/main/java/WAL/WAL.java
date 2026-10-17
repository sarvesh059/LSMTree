package WAL;

import core.Value;
import core.key.KeyCodec;
import memTable.MemTable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

public class WAL<K extends Comparable<K>> implements Closeable {
    private final KeyCodec<K> keyCodec;
    private final File walFile;
    private final RandomAccessFile fileStream;
    private long latestEventId = -1;
    private final ByteArrayOutputStream byteArrayOutputStream;
    private static final byte[] magic = "WAL2".getBytes(StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(WAL.class.getName());

    private static final int HEADER_SIZE = 20;
    private static final int MAGIC_OFFSET     = 0;
    private static final int CRC_OFFSET       = 4;
    private static final int TAG_OFFSET       = 8;
    private static final int LENGTH_OFFSET    = 16;
    private static final int CRC_COVERED_FROM = TAG_OFFSET;

    public WAL(KeyCodec<K> keyCodec, File walFile) throws IOException{
        this.keyCodec = keyCodec;
        this.walFile = walFile;
        this.fileStream = new RandomAccessFile(walFile, "rw");
        this.fileStream.seek(this.fileStream.length());
        this.byteArrayOutputStream = new ByteArrayOutputStream();
    }

    public void append(K key, Value value) throws IOException {
        byteArrayOutputStream.reset();
        DataOutputStream dataOutputStream = new DataOutputStream(this.byteArrayOutputStream);
        latestEventId = Math.max(latestEventId, value.getId());

        // WAL record format [magic][checksum][tag][len][payload]
        dataOutputStream.write(magic); //magic
        dataOutputStream.writeInt(0); //CRC placeholder
        dataOutputStream.writeLong(latestEventId); // tag
        dataOutputStream.writeInt(0); // length placeholder
        this.keyCodec.encode(key, dataOutputStream);
        value.writeTo(dataOutputStream);
        byte[] frame = byteArrayOutputStream.toByteArray();
        int payloadLength = frame.length - HEADER_SIZE;
        ByteBuffer.wrap(frame).putInt(LENGTH_OFFSET, payloadLength);
        ByteBuffer.wrap(frame).putInt(CRC_OFFSET, getCRC32CheckSum(frame, CRC_COVERED_FROM, frame.length - CRC_COVERED_FROM));
        this.fileStream.write(frame);

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
                byte[] header = new byte[HEADER_SIZE];
                in.readFully(header);

                byte[] recordMagicTag = new byte[magic.length];
                ByteBuffer.wrap(header).get(MAGIC_OFFSET, recordMagicTag);
                int readChecksum = ByteBuffer.wrap(header).getInt(CRC_OFFSET);
                if(!Arrays.equals(magic, recordMagicTag)){
                    return stopAndLog(WALReplayStopReason.BAD_MAGIC, offset, recordsRecovered);
                }
                long tag = ByteBuffer.wrap(header).getLong(TAG_OFFSET);
                if(tag < lastTag){
                    return stopAndLog(WALReplayStopReason.STALE_RECORD, offset, recordsRecovered);
                }
                lastTag = tag;
                int payloadLength = ByteBuffer.wrap(header).getInt(LENGTH_OFFSET);

                if(payloadLength <= 0){
                    return stopAndLog(WALReplayStopReason.INVALID_LENGTH, offset, recordsRecovered);
                }

                if(payloadLength > length-in.getFilePointer()){
                    return stopAndLog(WALReplayStopReason.TRUNCATED, offset, recordsRecovered);
                }

                byte[] payload = new byte[payloadLength];
                in.readFully(payload);

                byte[] frames = ByteBuffer.allocate(header.length + payload.length).put(header).put(payload).array();

                int computedChecksum = getCRC32CheckSum(frames, CRC_COVERED_FROM, frames.length - CRC_COVERED_FROM );
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

    private static int getCRC32CheckSum(byte[] data, int offset, int length){
        Checksum crc32 = new CRC32C();
        crc32.update(data, offset, length);
        return (int) crc32.getValue();
    }
}
