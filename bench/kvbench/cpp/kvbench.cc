// kvbench.cc — reference-engine side of the LSMTree benchmark kit.
//
// Runs the fixed workload contract (see GUIDE / README) against LevelDB or RocksDB.
// The Java harness (java/LSMTree/KvBench.java) runs the identical workload against LSMTree:
// same RNG (SplitMix64), same seed, same key/value generator, same phase order.
//
// Build: g++ -O2 -std=c++20 kvbench.cc -o kvbench -lrocksdb -lleveldb -lpthread
// Usage: ./kvbench --engine leveldb|rocksdb|system --db /path/to/data [--phases a,b,c] [--keys N] ...
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <latch>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <unistd.h>
#include <vector>

#include <leveldb/db.h>
#include <leveldb/filter_policy.h>
#include <rocksdb/db.h>
#include <rocksdb/filter_policy.h>
#include <rocksdb/table.h>

namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

// ---------------------------------------------------------------- workload primitives (must match Java)
struct SplitMix64 {
  uint64_t s;
  explicit SplitMix64(uint64_t seed) : s(seed) {}
  uint64_t next() {
    uint64_t z = (s += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
  }
  uint64_t below(uint64_t n) { return next() % n; }
};

static std::string key(int64_t i) {  // "user" + 12-digit zero-padded decimal = 16 bytes
  std::string k = "user000000000000";
  for (int p = 15; p >= 4 && i > 0; --p) { k[p] = char('0' + i % 10); i /= 10; }
  return k;
}
static std::string missKey(int64_t i) { return key(i) + "x"; }  // sorts between real keys
static std::string value(int64_t i) {  // 100 bytes: the key itself, then deterministic filler
  std::string v = key(i);
  v.resize(100);
  for (int j = 16; j < 100; j++) v[j] = char((i * 131 + j * 17) & 0xFF);
  return v;
}

// ---------------------------------------------------------------- engines
struct Visitor {
  virtual bool visit(std::string_view k, std::string_view v) = 0;
  virtual ~Visitor() {}
};
struct Kv {
  virtual void put(const std::string& k, const std::string& v, bool sync) = 0;
  virtual bool get(const std::string& k, std::string* v) = 0;
  virtual int scan(const std::string& low, const std::string& high, int limit, Visitor& vis) = 0;
  virtual void settle() = 0;
  virtual std::string describe() = 0;
  virtual ~Kv() {}
};

struct LevelKv : Kv {
  leveldb::DB* db = nullptr;
  const leveldb::FilterPolicy* fp = nullptr;
  explicit LevelKv(const std::string& path) {
    leveldb::Options o;
    o.create_if_missing = true;
    o.compression = leveldb::kNoCompression;  // contract: no compression anywhere
    fp = leveldb::NewBloomFilterPolicy(10);   // contract: 10 bits/key bloom filters
    o.filter_policy = fp;
    auto s = leveldb::DB::Open(o, path, &db);
    if (!s.ok()) { fprintf(stderr, "open failed: %s\n", s.ToString().c_str()); exit(1); }
  }
  void put(const std::string& k, const std::string& v, bool sync) override {
    leveldb::WriteOptions w; w.sync = sync;
    auto s = db->Put(w, k, v);
    if (!s.ok()) { fprintf(stderr, "put failed: %s\n", s.ToString().c_str()); exit(1); }
  }
  bool get(const std::string& k, std::string* v) override { return db->Get(leveldb::ReadOptions(), k, v).ok(); }
  int scan(const std::string& low, const std::string& high, int limit, Visitor& vis) override {
    std::unique_ptr<leveldb::Iterator> it(db->NewIterator(leveldb::ReadOptions()));
    int n = 0;
    for (it->Seek(low); it->Valid() && n < limit && it->key().compare(high) <= 0; it->Next()) {
      if (!vis.visit(std::string_view(it->key().data(), it->key().size()), std::string_view(it->value().data(), it->value().size()))) break;
      n++;
    }
    return n;
  }
  void settle() override { db->CompactRange(nullptr, nullptr); }  // flushes memtable + full compaction, synchronous
  std::string describe() override { return "leveldb defaults, compression=none, bloom=10bits/key"; }
  ~LevelKv() override { delete db; delete fp; }
};

struct RocksKv : Kv {
  rocksdb::DB* db = nullptr;
  explicit RocksKv(const std::string& path) {
    rocksdb::Options o;
    o.create_if_missing = true;
    o.compression = rocksdb::kNoCompression;
    rocksdb::BlockBasedTableOptions t;
    t.filter_policy.reset(rocksdb::NewBloomFilterPolicy(10));
    o.table_factory.reset(rocksdb::NewBlockBasedTableFactory(t));
    auto s = rocksdb::DB::Open(o, path, &db);
    if (!s.ok()) { fprintf(stderr, "open failed: %s\n", s.ToString().c_str()); exit(1); }
  }
  void put(const std::string& k, const std::string& v, bool sync) override {
    rocksdb::WriteOptions w; w.sync = sync;
    auto s = db->Put(w, k, v);
    if (!s.ok()) { fprintf(stderr, "put failed: %s\n", s.ToString().c_str()); exit(1); }
  }
  bool get(const std::string& k, std::string* v) override { return db->Get(rocksdb::ReadOptions(), k, v).ok(); }
  int scan(const std::string& low, const std::string& high, int limit, Visitor& vis) override {
    std::unique_ptr<rocksdb::Iterator> it(db->NewIterator(rocksdb::ReadOptions()));
    int n = 0;
    for (it->Seek(low); it->Valid() && n < limit && it->key().compare(high) <= 0; it->Next()) {
      if (!vis.visit(std::string_view(it->key().data(), it->key().size()), std::string_view(it->value().data(), it->value().size()))) break;
      n++;
    }
    return n;
  }
  void settle() override {
    db->Flush(rocksdb::FlushOptions());
    db->CompactRange(rocksdb::CompactRangeOptions(), nullptr, nullptr);
  }
  std::string describe() override { return "rocksdb defaults, compression=none, bloom=10bits/key"; }
  ~RocksKv() override { delete db; }
};

// ---------------------------------------------------------------- harness
struct Args {
  std::string engine, db;
  std::set<std::string> phases;
  long keys = 100000, hits = 100000, misses = 100000, scans = 10000, warmup = 20000;
  int scanLen = 100, writers = 8;
  long perWriter = 5000, fsyncOps = 2000;
  uint64_t seed = 42;
};

static const char* ALL_PHASES = "fsync_floor,fill_nosync,fill_sync,settle,read_hit,read_miss,scan100,fill_sync_mt";

static Args parse(int argc, char** argv) {
  Args a;
  std::string phases = ALL_PHASES;
  for (int i = 1; i < argc; i++) {
    std::string f = argv[i];
    auto next = [&]() -> std::string { if (i + 1 >= argc) { fprintf(stderr, "missing value for %s\n", f.c_str()); exit(64); } return argv[++i]; };
    if (f == "--engine") a.engine = next();
    else if (f == "--db") a.db = next();
    else if (f == "--phases") phases = next();
    else if (f == "--keys") a.keys = std::stol(next());
    else if (f == "--hits") a.hits = std::stol(next());
    else if (f == "--misses") a.misses = std::stol(next());
    else if (f == "--scans") a.scans = std::stol(next());
    else if (f == "--warmup") a.warmup = std::stol(next());
    else if (f == "--writers") a.writers = std::stoi(next());
    else if (f == "--per-writer") a.perWriter = std::stol(next());
    else if (f == "--seed") a.seed = std::stoull(next());
    else { fprintf(stderr, "unknown flag %s\n", f.c_str()); exit(64); }
  }
  if (a.engine.empty() || a.db.empty()) { fprintf(stderr, "usage: kvbench --engine leveldb|rocksdb|system --db DIR [--phases %s]\n", ALL_PHASES); exit(64); }
  std::stringstream ss(phases); std::string p;
  while (std::getline(ss, p, ',')) if (!p.empty()) a.phases.insert(p);
  return a;
}

static bool want(const Args& a, const char* p) { return a.phases.count(p) > 0; }

static void report(const std::string& engine, const char* phase, std::vector<int64_t>& ns, double seconds) {
  std::sort(ns.begin(), ns.end());
  size_t n = ns.size();
  if (n == 0) return;
  printf("RESULT engine=%s phase=%s ops=%zu ops_per_sec=%.0f p50_us=%.2f p99_us=%.2f max_us=%.1f\n",
         engine.c_str(), phase, n, n / seconds, ns[n / 2] / 1e3, ns[std::min(n - 1, (size_t)(n * 0.99))] / 1e3, ns[n - 1] / 1e3);
  fflush(stdout);
}

[[noreturn]] static void verifyFailed(const std::string& engine, const char* phase, const std::string& detail) {
  printf("VERIFY_FAILED engine=%s phase=%s detail=%s\n", engine.c_str(), phase, detail.c_str());
  fflush(stdout);
  exit(2);
}

static Kv* openFresh(const std::string& engine, const std::string& path) {
  fs::remove_all(path);
  fs::create_directories(fs::path(path).parent_path());
  if (engine == "leveldb") return new LevelKv(path);
  if (engine == "rocksdb") return new RocksKv(path);
  fprintf(stderr, "unknown engine %s\n", engine.c_str());
  exit(64);
}

static uint64_t dirBytes(const std::string& path) {
  uint64_t total = 0;
  for (auto& e : fs::recursive_directory_iterator(path))
    if (e.is_regular_file()) total += e.file_size();
  return total;
}

static void fsyncFloor(const Args& a) {
  fs::create_directories(a.db);
  for (int mode = 0; mode < 2; mode++) {
    std::string path = a.db + "/fsync-probe";
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { perror("open"); exit(1); }
    char buf[120]; memset(buf, 'x', sizeof buf);
    std::vector<int64_t> ns; ns.reserve(a.fsyncOps);
    auto t0 = Clock::now();
    for (long i = 0; i < a.fsyncOps; i++) {
      auto s = Clock::now();
      if (write(fd, buf, sizeof buf) != (ssize_t)sizeof buf) { perror("write"); exit(1); }
      if ((mode == 0 ? fsync(fd) : fdatasync(fd)) != 0) { perror("sync"); exit(1); }
      ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - s).count());
    }
    double secs = std::chrono::duration<double>(Clock::now() - t0).count();
    close(fd);
    unlink(path.c_str());
    report("system", mode == 0 ? "fsync_floor" : "fdatasync_floor", ns, secs);
  }
}

int main(int argc, char** argv) {
  Args a = parse(argc, argv);
  if (want(a, "fsync_floor") || a.engine == "system") fsyncFloor(a);
  if (a.engine == "system") return 0;
  const std::string& E = a.engine;

  // Pre-generate everything outside the timed loops, from one seeded RNG, in a fixed order.
  SplitMix64 rng(a.seed);
  std::vector<int64_t> order(a.keys);
  for (long i = 0; i < a.keys; i++) order[i] = i;
  for (long i = a.keys - 1; i > 0; i--) std::swap(order[i], order[rng.below(i + 1)]);  // Fisher-Yates
  std::vector<std::string> keys(a.keys), vals(a.keys);
  for (long i = 0; i < a.keys; i++) { keys[i] = key(i); vals[i] = value(i); }

  if (want(a, "fill_nosync")) {
    Kv* kv = openFresh(E, a.db + "/" + E + "-nosync");
    std::vector<int64_t> ns; ns.reserve(a.keys);
    auto t0 = Clock::now();
    for (long i : order) { auto s = Clock::now(); kv->put(keys[i], vals[i], false); ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - s).count()); }
    report(E, "fill_nosync", ns, std::chrono::duration<double>(Clock::now() - t0).count());
    delete kv;
  }

  bool needLoad = want(a, "fill_sync") || want(a, "settle") || want(a, "read_hit") || want(a, "read_miss") || want(a, "scan100");
  if (needLoad) {
    std::string path = a.db + "/" + E;
    Kv* kv = openFresh(E, path);
    printf("INFO engine=%s config=\"%s\"\n", E.c_str(), kv->describe().c_str());
    std::vector<int64_t> ns; ns.reserve(a.keys);
    auto t0 = Clock::now();
    for (long i : order) { auto s = Clock::now(); kv->put(keys[i], vals[i], true); ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - s).count()); }
    report(E, "fill_sync", ns, std::chrono::duration<double>(Clock::now() - t0).count());

    auto s0 = Clock::now();
    kv->settle();
    double settleSecs = std::chrono::duration<double>(Clock::now() - s0).count();
    printf("RESULT engine=%s phase=settle seconds=%.3f disk_mb=%.2f logical_mb=%.2f\n", E.c_str(), settleSecs,
           dirBytes(path) / 1048576.0, a.keys * 116 / 1048576.0);

    std::vector<int64_t> hitIdx(a.hits), missIdx(a.misses), scanIdx(a.scans), warm(a.warmup);
    for (auto& x : warm) x = rng.below(a.keys);
    for (auto& x : hitIdx) x = rng.below(a.keys);
    for (auto& x : missIdx) x = rng.below(a.keys);
    for (auto& x : scanIdx) x = rng.below(a.keys - a.scanLen);
    std::vector<std::string> missKeys(a.misses);
    for (long i = 0; i < a.misses; i++) missKeys[i] = missKey(missIdx[i]);
    std::string out;

    if (want(a, "read_hit")) {
      for (long i : warm) kv->get(keys[i], &out);
      ns.clear(); ns.reserve(a.hits);
      auto t = Clock::now();
      for (long i : hitIdx) {
        auto s = Clock::now();
        bool ok = kv->get(keys[i], &out);
        ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - s).count());
        if (!ok || out != vals[i]) verifyFailed(E, "read_hit", "wrong or missing value for " + keys[i]);
      }
      report(E, "read_hit", ns, std::chrono::duration<double>(Clock::now() - t).count());
    }
    if (want(a, "read_miss")) {
      for (long i = 0; i < std::min(a.warmup, a.misses); i++) kv->get(missKeys[i], &out);
      ns.clear(); ns.reserve(a.misses);
      auto t = Clock::now();
      for (long i = 0; i < a.misses; i++) {
        auto s = Clock::now();
        bool found = kv->get(missKeys[i], &out);
        ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - s).count());
        if (found) verifyFailed(E, "read_miss", "found a key that was never written: " + missKeys[i]);
      }
      report(E, "read_miss", ns, std::chrono::duration<double>(Clock::now() - t).count());
    }
    if (want(a, "scan100")) {
      struct Check : Visitor {
        const std::string* low; char last[64]; size_t lastLen = 0; int seen = 0; bool bad = false;
        bool visit(std::string_view k, std::string_view v) override {
          if ((seen == 0 && k != *low) || (seen > 0 && k <= std::string_view(last, lastLen)) || v.size() != 100 || v.substr(0, 16) != k.substr(0, 16) || k.size() > sizeof last) { bad = true; return false; }
          memcpy(last, k.data(), k.size()); lastLen = k.size(); seen++; return true;
        }
      };
      for (long w = 0; w < a.warmup / 20; w++) { Check c; c.low = &keys[scanIdx[w % a.scans]]; kv->scan(keys[scanIdx[w % a.scans]], keys[scanIdx[w % a.scans] + a.scanLen - 1], a.scanLen, c); }
      ns.clear(); ns.reserve(a.scans);
      auto t = Clock::now();
      for (long i : scanIdx) {
        Check c; c.low = &keys[i];
        auto s = Clock::now();
        int n = kv->scan(keys[i], keys[i + a.scanLen - 1], a.scanLen, c);
        ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - s).count());
        if (c.bad || n != a.scanLen) verifyFailed(E, "scan100", "scan from " + keys[i] + " returned " + std::to_string(n) + " valid rows");
      }
      report(E, "scan100", ns, std::chrono::duration<double>(Clock::now() - t).count());
    }
    delete kv;
  }

  if (want(a, "fill_sync_mt")) {
    Kv* kv = openFresh(E, a.db + "/" + E + "-mt");
    std::vector<std::vector<std::pair<std::string, std::string>>> work(a.writers);
    for (int t = 0; t < a.writers; t++)
      for (long i = 0; i < a.perWriter; i++) { int64_t id = 1000000000LL + (int64_t)t * 1000000 + i; work[t].emplace_back(key(id), value(id)); }
    std::latch start(a.writers + 1);
    std::vector<std::thread> ts;
    for (int t = 0; t < a.writers; t++)
      ts.emplace_back([&, t] { start.arrive_and_wait(); for (auto& kvp : work[t]) kv->put(kvp.first, kvp.second, true); });
    auto t0 = Clock::now();
    start.arrive_and_wait();
    for (auto& th : ts) th.join();
    double secs = std::chrono::duration<double>(Clock::now() - t0).count();
    long total = a.writers * a.perWriter;
    std::string out;
    for (int t = 0; t < a.writers; t++)  // verify after timing
      for (auto& kvp : work[t]) if (!kv->get(kvp.first, &out) || out != kvp.second) verifyFailed(E, "fill_sync_mt", "lost write " + kvp.first);
    printf("RESULT engine=%s phase=fill_sync_mt ops=%ld ops_per_sec=%.0f writers=%d\n", E.c_str(), total, total / secs, a.writers);
    delete kv;
  }
  return 0;
}
