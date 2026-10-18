package wal;

public enum WALReplayStopReason {
    CLEAN_EOF, TRUNCATED, BAD_MAGIC, INVALID_LENGTH, CHECKSUM_MISMATCH, STALE_RECORD;

    public System.Logger.Level logLevel(){
        return switch (this){
            case CLEAN_EOF -> System.Logger.Level.INFO;
            case TRUNCATED -> System.Logger.Level.WARNING;
            case BAD_MAGIC -> System.Logger.Level.ERROR;
            case INVALID_LENGTH -> System.Logger.Level.ERROR;
            case CHECKSUM_MISMATCH -> System.Logger.Level.ERROR;
            case STALE_RECORD -> System.Logger.Level.ERROR;
        };
    }
}
