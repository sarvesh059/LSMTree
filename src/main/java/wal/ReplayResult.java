package wal;

public record ReplayResult (long recordsRecovered, WALReplayStopReason stopReason, long offset){}
