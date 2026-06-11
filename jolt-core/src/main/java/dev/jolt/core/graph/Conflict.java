package dev.jolt.core.graph;

import java.util.List;

public record Conflict(GA coordinate, List<String> requestedVersions, String selectedVersion) {

    @Override
    public String toString() {
        return coordinate + " requested=" + requestedVersions + " selected=" + selectedVersion;
    }
}
