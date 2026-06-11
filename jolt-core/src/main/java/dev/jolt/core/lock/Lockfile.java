package dev.jolt.core.lock;

import java.time.Instant;
import java.util.List;

public record Lockfile(
        int version,
        String generatedWith,
        Instant generatedAt,
        String root,
        String buildSystem,
        List<LockEntry> packages
) {
    public static final int CURRENT_VERSION = 1;
}
