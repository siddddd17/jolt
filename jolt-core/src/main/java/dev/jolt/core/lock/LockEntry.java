package dev.jolt.core.lock;

import java.util.List;

public record LockEntry(
        String coordinate,   // group:artifact:version
        String scope,
        String repository,
        String sha256,
        String pomSha256,
        List<String> requestedBy,
        List<String> parents,
        String module        // module name (artifactId); empty string for single-module projects
) {}
