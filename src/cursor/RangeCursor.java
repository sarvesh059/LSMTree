package cursor;

import RBT.Entry;
import core.Value;
import core.key.KeyCodec;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class RangeCursor<K extends Comparable<K>> implements EntrySource<K>{
    private final byte[] low;
    private final byte[] high;
    private final RandomAccessFile input;
    private final KeyCodec<K> keyCodec;
    private final int totalEntries;
    private long maxEventId;

    public RangeCursor(byte[] low, byte[] high, File dataFile, KeyCodec<K> keyCodec) throws IOException{
        this.low = low;
        this.high = high;
        this.input = new RandomAccessFile(dataFile, "r");
        this.totalEntries = this.input.readInt();
        this.input.readInt(); // level, not needed by this cursor
        this.maxEventId = this.input.readLong();
        this.keyCodec = keyCodec;
    }

    @Override
    public boolean hasNext() throws IOException {
        if(this.totalEntries == 0) return false;
        long pos = this.input.getFilePointer();
        try{
            byte[] key = this.keyCodec.readRawEncoded(this.input);
            if(this.keyCodec.compareEncoded(low, key) <= 0 && this.keyCodec.compareEncoded(high, key) >= 0){
                this.seek(pos);
                return true;
            }
        } catch (EOFException _){
        }
        this.seek(pos);
        return false;
    }

    @Override
    public Entry<K, Value> next() throws IOException {
        if(this.totalEntries == 0) return null;
        try{
            byte[] key = this.keyCodec.readRawEncoded(this.input);
            if(this.keyCodec.compareEncoded(low, key) <= 0 && this.keyCodec.compareEncoded(high, key) >= 0){
                Value val = Value.readFrom(this.input);
                this.maxEventId = Math.max(this.maxEventId, val.getId());
                return new Entry<>(this.keyCodec.decodeKey(key), val);
            }
            return null;
        } catch (EOFException _){
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        this.input.close();
    }

    public void seek(long offset) throws IOException {
        this.input.seek(offset);
    }
}
