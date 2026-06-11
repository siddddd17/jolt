package dev.jolt.core.graph;

public record GAV(String groupId, String artifactId, String version) {

    public static GAV parse(String coords) {
        String[] parts = coords.split(":");
        if (parts.length < 3) throw new IllegalArgumentException("Expected group:artifact:version, got: " + coords);
        return new GAV(parts[0], parts[1], parts[2]);
    }

    public GA ga() {
        return new GA(groupId, artifactId);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
