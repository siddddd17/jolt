package dev.jolt.core.graph;

public record GA(String groupId, String artifactId) {

    public static GA parse(String coords) {
        String[] parts = coords.split(":");
        if (parts.length < 2) throw new IllegalArgumentException("Expected group:artifact, got: " + coords);
        return new GA(parts[0], parts[1]);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
