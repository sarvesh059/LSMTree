package bloomFilter;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.BitSet;

public class BloomFilter {
    private final int bitArraySize;
    private final int totalHashFunctions;
    private final BitSet bits;

    private final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private final long FNV_PRIME = 0x100000001b3L;
    private final long H2_SEED = 0x9e3779b97f4a7c15L;

    public BloomFilter(int expectedEntries, double falsePositiveRate){
        this.bitArraySize = computeBitArraySize(expectedEntries, falsePositiveRate);
        this.totalHashFunctions = computeTotalHashFunctions(this.bitArraySize, expectedEntries);
        this.bits = new BitSet(this.bitArraySize);
    }

    public BloomFilter(int bitArraySize, int totalHashFunctions, BitSet bits){
        this.bitArraySize = bitArraySize;
        this.totalHashFunctions = totalHashFunctions;
        this.bits = bits;
    }

    int computeBitArraySize(int expectedEntries, double falsePositiveRate){
        int m = (int) Math.ceil(-(expectedEntries * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
        return (m % 2 == 0) ? m + 1 : m;
    }

    int computeTotalHashFunctions(int bitArraySize, int expectedEntries){
        return Math.max(1, (int) Math.round((double) bitArraySize/ expectedEntries * Math.log(2)));
    }

    public void add(byte[] encodedKey){
        long h1 = fnv1a(encodedKey, FNV_OFFSET_BASIS);
        long h2 = fnv1a(encodedKey, H2_SEED);
        for(int i=0;i<totalHashFunctions;i++){
            bits.set(hash(h1, h2, i));
        }
    }

    int hash(long h1, long h2, int i){
        long combined = h1 + (long) i* h2;
        return (int) Math.floorMod(combined,this.bitArraySize);
    }

    //Fowler-Noll-Vo hash
    long fnv1a(byte[] data, long offsetBasis){
        long hash = offsetBasis;
        for(byte b: data){
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    public boolean mightContain(byte[] encodedKey){
        long h1 = fnv1a(encodedKey, FNV_OFFSET_BASIS);
        long h2 = fnv1a(encodedKey, H2_SEED);
        for(int i=0;i<this.totalHashFunctions;i++){
            if(!bits.get(hash(h1, h2, i))) return false;
        }
        return true;
    }

    public void writeTo(DataOutput out) throws IOException{
        out.writeInt(this.bitArraySize);
        out.writeInt(this.totalHashFunctions);
        byte[] bitBytes = bits.toByteArray();
        out.writeInt(bitBytes.length);
        out.write(bitBytes);
    }

    public static BloomFilter readFrom(DataInput in) throws IOException{
        int bitArraySize = in.readInt();
        int totalHashFunctions = in.readInt();
        byte[] bitBytes = new byte[in.readInt()];
        in.readFully(bitBytes);
        return new BloomFilter(bitArraySize, totalHashFunctions, BitSet.valueOf(bitBytes));
    }
}
