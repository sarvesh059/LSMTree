package WAL;

public record ReplayResult (long recordsRecovered, WALReplayStopReason stopReason, long offset){}
