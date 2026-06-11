package dev.jolt.core.project;

public enum BuildSystem {
    MAVEN, GRADLE, UNKNOWN;

    public String displayName() {
        return switch (this) {
            case MAVEN   -> "Maven";
            case GRADLE  -> "Gradle";
            case UNKNOWN -> "Unknown";
        };
    }
}
