package core;

import java.io.*;
import java.util.Arrays;
import java.util.Objects;

public class Value {
    static final long UNSEQUENCED = -1L;

    boolean tombstone;
    byte[] payload;
    int sizeInBytes = 1+8;
    final long id;

    private Value(long id, byte[] payload, boolean tombstone){
        if(payload != null){
            this.payload = payload.clone();
            this.sizeInBytes += payload.length + 4 ;
        }
        this.tombstone = tombstone;
        this.id = id;
    }

    public static Value of(byte[] data){
        return new Value(UNSEQUENCED, data, false);
    }

    public static Value tombstone(){
        return new Value(UNSEQUENCED, null, true);
    }

    public static Value of(long id, byte[] data){
        return new Value(id, data, false);
    }

    public static Value tombstone(long id){
        return new Value(id,null, true);
    }

    public boolean isTombstone(){
        return this.tombstone;
    }

    public byte[] getData(){
        if(tombstone) throw new IllegalStateException("tombstone has no payload");
        return payload.clone();
    }

    public long getId(){
        return this.id;
    }

    public void writeTo(DataOutput out) throws IOException{
        out.writeLong(this.id);
        out.writeBoolean(tombstone);
        if(!tombstone){
            out.writeInt(this.payload.length);
            out.write(payload);
        }
    }

    public static Value readFrom(DataInput in) throws IOException{
        long id = in.readLong();
        boolean isTombstone = in.readBoolean();
        if(isTombstone) return Value.tombstone(id);

        int length = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);
        return Value.of(id, data);
    }

    public static Value withId(long id, Value value){
        return new Value(id, value.tombstone ? null : value.payload, value.tombstone);
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
