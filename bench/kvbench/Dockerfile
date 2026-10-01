# Benchmark environment for the LSMTree kit: Ubuntu 24.04, JDK 25, LevelDB 1.23, RocksDB 8.9.1.
#   docker build -t lsm-bench bench/kvbench
#   docker run --rm -it --cpuset-cpus=0 --cap-add=SYS_PTRACE \
#       -v "$PWD":/repo -v lsm-bench-data:/data -e DATA_DIR=/data/kvbench -w /repo lsm-bench \
#       bench/kvbench/scripts/run_all.sh
# Keep DATA_DIR on the named volume (/data), never on the bind-mounted repo: on macOS/Windows hosts,
# bind mounts go through a file-sharing layer with very different fsync and read behavior.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-25-jdk-headless g++ librocksdb-dev libleveldb-dev \
        python3 strace git curl ca-certificates procps util-linux \
    && rm -rf /var/lib/apt/lists/*
RUN git config --global --add safe.directory '*'
WORKDIR /repo
