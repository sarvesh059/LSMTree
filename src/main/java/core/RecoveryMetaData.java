package core;

import manifest.Manifest;

public record RecoveryMetaData(Manifest manifest, long maxEventId) {
}
