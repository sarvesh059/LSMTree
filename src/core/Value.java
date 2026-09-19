package core;

import java.io.*;
import java.util.Arrays;
import java.util.Objects;

public class Value {
    boolean tombstone;
    byte[] payload;
    int sizeInBytes = 1;

    private Value(byte[] payload, boolean tombstone){
        if(payload != null){
            this.payload = payload.clone();
            this.sizeInBytes += payload.length + 4 ;
        }
        this.tombstone = tombstone;
    }

    public static Value of(byte[] data){
        return new Value(data, false);
    }

    public static Value tombstone(){
        return new Value(null, true);
    }

    public boolean isTombstone(){
        return this.tombstone;
    }

    public byte[] getData(){
        if(tombstone) throw new IllegalStateException("tombstone has no payload");
        return payload.clone();
    }

    public void writeTo(DataOutput out) throws IOException{
        out.writeBoolean(tombstone);
        if(!tombstone){
            out.writeInt(this.payload.length);
            out.write(payload);
        }
    }

    public static Value readFrom(DataInput in) throws IOException{
        boolean isTombstone = in.readBoolean();
        if(isTombstone) return Value.tombstone();

        int length = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);
        return Value.of(data);
    }

    public int getSizeInBytes(){
        return this.sizeInBytes;
    }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof  Value other)) return false;
        return tombstone == other.tombstone && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tombstone, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return tombstone
                ? "Value{tombstone=true}"
                : "Value{tombstone=false, payload=" + Arrays.toString(payload) + "}";
    }
}
