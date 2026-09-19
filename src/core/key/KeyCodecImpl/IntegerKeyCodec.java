package core.key.KeyCodecImpl;

import core.key.KeyCodec;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class IntegerKeyCodec implements KeyCodec<Integer> {
    @Override
    public byte[] encode(Integer key, DataOutput out) throws IOException {
        byte[] encodedKey = encodeKey(key);
        out.write(encodedKey);
        return encodedKey;
    }

    @Override
    public Integer decode(DataInput in) throws IOException {
        return ByteBuffer.wrap(readRawEncoded(in)).getInt()^Integer.MIN_VALUE;
    }

    @Override
    public int compareEncoded(byte[] a, byte[] b){
        return Arrays.compareUnsigned(a, b);
    }

    @Override
    public byte[] readRawEncoded(DataInput in) throws IOException {
        byte[] data = new byte[4];
        in.readFully(data);
        return data;
    }

    @Override
    public Integer decodeKey(byte[] encodedKey) {
        return ByteBuffer.wrap(encodedKey).getInt()^Integer.MIN_VALUE;
    }

    @Override
    public byte[] encodeKey(Integer key) {
        int xor = key ^ Integer.MIN_VALUE;
        return ByteBuffer.allocate(4).putInt(xor).array();
    }
}
