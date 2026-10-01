### I/O syscalls per operation (2000 ops per phase; per scan = one 100-key scan)

| Engine | Phase | Syscalls per op | Top syscalls per op |
|---|---|---|---|
| candidate | read_hit | 193.2 | read 189.1, close 1.0, openat 1.0, fstat 1.0 |
| candidate | read_miss | 3.7 | read 3.6 |
| candidate | scan100 | 4422.4 | read 4204.9, lseek 211.4, close 2.0, openat 2.0 |
| leveldb | read_hit | 0.0 |  |
| leveldb | read_miss | 0.0 |  |
| leveldb | scan100 | 0.0 |  |
| rocksdb | read_hit | 0.7 | pread64 0.7 |
| rocksdb | read_miss | 0.0 |  |
| rocksdb | scan100 | 2.3 | pread64 1.3, readahead 1.0 |
