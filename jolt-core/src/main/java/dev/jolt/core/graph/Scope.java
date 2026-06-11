package dev.jolt.core.graph;

public enum Scope {
    COMPILE, PROVIDED, RUNTIME, TEST, SYSTEM, IMPORT;

    public static Scope of(String value) {
        if (value == null || value.isBlank()) return COMPILE;
        return valueOf(value.toUpperCase());
    }
}
