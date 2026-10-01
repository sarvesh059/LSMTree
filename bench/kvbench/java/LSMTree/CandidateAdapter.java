package LSMTree;

import RBT.Entry;
import compaction.CompactStrategyImpl.LeveledCompactionStrategy;
import compaction.MergeStrategyImpl.LeveledMergeStrategy;
import core.Segment;
import core.Value;
import core.key.KeyCodecImpl.StringKeyCodec;
import cursor.EntrySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The ONLY file you should need to edit as the engine's API changes.
 * Keep the semantics each method promises in KvBench.Kv; change how it is achieved freely.
 * Record every engine setting you change in describe(), so it's printed next to your results.
 *
 * Starting configuration mirrors LevelDB defaults: 4 MiB memtable, a sparse-index entry every
 * 16 keys (~LevelDB's restart interval), leveled compaction with 2 MiB target files.
 */
final class CandidateAdapter implements KvBench.Kv {
    private static final int MEMTABLE_BYTES = 4 * 1024 * 1024;
    private static final int INDEX_EVERY = 16;
    private static final long TARGET_FILE_BYTES = 2L * 1024 * 1024;

    private final LSMTree<String> tree;

    private CandidateAdapter(Path dir) throws IOException {
        StringKeyCodec codec = new StringKeyCodec();
        this.tree = new LSMTree<>(codec, dir, INDEX_EVERY, MEMTABLE_BYTES,
                new LeveledCompactionStrategy<>(), new LeveledMergeStrategy<>(codec, TARGET_FILE_BYTES));
    }

    static KvBench.Kv open(Path dir) throws IOException { return new CandidateAdapter(dir); }

    @Override public void put(String key, byte[] value, boolean sync) throws IOException {
        if (!sync) throw new UnsupportedOperationException("put() always fsyncs; add a non-durable write option to enable fill_nosync");
        tree.put(key, Value.of(value));
    }

    @Override public byte[] get(String key) throws IOException {
        Value v = tree.get(key);
        return (v == null || v.isTombstone()) ? null : v.getData();
    }

    @Override public int scan(String low, String high, int limit, KvBench.Visitor visitor) throws IOException {
        int n = 0;
        try (EntrySource<String> c = tree.scan(low, high)) {
            while (n < limit && c.hasNext()) {
                Entry<String, Value> e = c.next();
                if (e == null) break;
                if (!visitor.visit(e.getKey(), e.getValue().getData())) break;
                n++;
            }
        }
        return n;
    }

    @Override public void settle() throws IOException {
        try {
            tree.flush();
            tree.awaitCompaction();
            List<Segment<String>> live = tree.segments().stream().filter(s -> !s.isDeleted()).toList();
            if (live.size() > 1) tree.sharedCompactionLogic(live);   // full compaction, like CompactRange(all)
            tree.awaitCompaction();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        Map<Integer, Integer> perLevel = new TreeMap<>();
        for (Segment<String> s : tree.segments()) if (!s.isDeleted()) perLevel.merge(s.getLevel(), 1, Integer::sum);
        System.out.println("INFO engine=candidate segments_per_level=" + perLevel);
    }

    @Override public String describe() {
        return "memtable=" + (MEMTABLE_BYTES >> 20) + "MiB indexEvery=" + INDEX_EVERY
                + " compaction=leveled targetFile=" + (TARGET_FILE_BYTES >> 20) + "MiB";
    }

    @Override public void close() throws IOException { tree.close(); }
}
