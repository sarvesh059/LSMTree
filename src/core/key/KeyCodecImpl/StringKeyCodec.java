package core.key.KeyCodecImpl;

import core.key.KeyCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class StringKeyCodec implements KeyCodec<String> {
    @Override
    public byte[] encode(String key, DataOutput out) throws IOException {
        byte[] encodedKey = encodeKey(key);

        out.write(encodedKey);

        return encodedKey;
    }

    @Override
    public String decode(DataInput in) throws IOException {
        return new String(clean(readRawEncoded(in)), StandardCharsets.UTF_8);
    }

    @Override
    public int compareEncoded(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    @Override
    public byte[] readRawEncoded(DataInput in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while(true){
            byte b = in.readByte();
            if(b == 0x00){
                byte next = in.readByte();
                if(next == 0x00){
                    buffer.write(b);
                    buffer.write(b);
                    break;
                }
                buffer.write(b);
                buffer.write(next);
            } else{
                buffer.write(b);
            }
        }

        return buffer.toByteArray();
    }

    @Override
    public String decodeKey(byte[] encodedKey) {
        return new String(clean(encodedKey), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] encodeKey(String key) {
        byte[] utf8 = key.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (byte b : utf8) {
            if (b == 0x00) {
                buffer.write(0x00);
                buffer.write(0xFF);
            } else {
                buffer.write(b);
            }
        }

        buffer.write(0x00);
        buffer.write(0x00);

        return buffer.toByteArray();
    }

    public byte[] clean(byte[] arr){
        int n = arr.length;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int i=0;
        while(i<n){
            if(arr[i] == 0x00){
                if(i+1 < n && arr[i+1] == 0x00) return buffer.toByteArray();
                buffer.write(arr[i]);
                i++;
            }else{
                buffer.write(arr[i]);
            }
            i++;
        }

        return buffer.toByteArray();
    }
}
