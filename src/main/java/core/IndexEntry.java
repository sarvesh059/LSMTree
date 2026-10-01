package core;

import core.key.KeyCodec;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class IndexEntry {
    private final long offset;
    private final byte[] encodedKey;

    public long getOffset() {
        return offset;
    }

    public IndexEntry(byte[] encodedKey, long offset) {
        this.offset = offset;
        this.encodedKey = encodedKey;
    }

    public byte[] getEncodedKey(){
        return this.encodedKey;
    }

    public <K> K getDecodedKey(KeyCodec<K> codec) {
        return codec.decodeKey(this.encodedKey);
    }

    public <K> void writeTo(DataOutput out, KeyCodec<K> keyCodec) throws IOException {
        out.write(this.encodedKey);
        out.writeLong(this.offset);
    }

    public static <K> IndexEntry readFrom(DataInput in, KeyCodec<K> keyCodec) throws IOException {
        byte[] key = keyCodec.readRawEncoded(in);
        long offset = in.readLong();
        return new IndexEntry(key, offset);
    }
}
