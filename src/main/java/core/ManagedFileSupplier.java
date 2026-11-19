package core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class ManagedFileSupplier implements Closeable, Supplier<SegmentFiles> {
    private boolean shouldRollback = true;
    private final List<SegmentFiles> filesSupplied = new ArrayList<>();
    private final Supplier<SegmentFiles> delegate;

    public ManagedFileSupplier(Supplier<SegmentFiles> delegate) {
        this.delegate = delegate;
    }

    @Override
    public SegmentFiles get() {
        SegmentFiles segmentFiles = this.delegate.get();
        filesSupplied.add(segmentFiles);
        return segmentFiles;
    }

    public void commit(){
        this.shouldRollback = false;
    }

    private void rollBack() throws IOException {
        List<Path> filePaths = filesSupplied.stream()
                .flatMap(segmentFiles -> Stream.of(segmentFiles.dataFile(), segmentFiles.indexFile(), segmentFiles.bloomFilterFile()))
                .map(File::toPath)
                .toList();
        FileUtils.cleanUpFiles(filePaths);
    }

    @Override
    public void close() throws IOException {
        if(shouldRollback){
            rollBack();
        }
    }
}
