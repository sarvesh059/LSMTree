package WAL;

import core.Value;
import core.key.KeyCodec;
import memTable.MemTable;

import java.io.*;

public class WAL<K extends Comparable<K>> implements Closeable {
    private final KeyCodec<K> keyCodec;
    private final File walFile;
    private final RandomAccessFile fileStream;

    public WAL(KeyCodec<K> keyCodec, File walFile) throws IOException{
        this.keyCodec = keyCodec;
        this.walFile = walFile;
        this.fileStream = new RandomAccessFile(walFile, "rw");
        this.fileStream.seek(this.fileStream.length());
    }

    public void append(K key, Value value) throws IOException {
        this.keyCodec.encode(key, this.fileStream);
        value.writeTo(this.fileStream);
    }

    public void replay(MemTable<K> targetMemTable) throws IOException{
        try(RandomAccessFile in = new RandomAccessFile(this.walFile, "r")){
            while (true) {
                try {
                    K key = this.keyCodec.decode(in);
                    Value value = Value.readFrom(in);
                    targetMemTable.put(key, value);
                } catch (EOFException e) {
                    break;
                }
            }
        }
    }

    public void fsync() throws IOException {
        this.fileStream.getFD().sync();
    }

    public void reset() throws IOException {
        this.fileStream.setLength(0);
        this.fileStream.seek(0);
    }

    @Override
    public void close() throws IOException{
        this.fileStream.close();
    }
}
