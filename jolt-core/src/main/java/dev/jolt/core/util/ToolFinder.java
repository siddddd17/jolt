package dev.jolt.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class ToolFinder {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private ToolFinder() {}

    public static Optional<Path> find(String name) {
        // 1. Well-known env vars for specific tools
        String envPath = switch (name) {
            case "mvn"    -> envDir("MAVEN_HOME", "M2_HOME");
            case "java"   -> envDir("JAVA_HOME");
            case "docker" -> null;
            case "git"    -> null;
            default       -> null;
        };
        if (envPath != null) {
            var candidate = Path.of(envPath, "bin", executable(name));
            if (Files.isExecutable(candidate)) return Optional.of(candidate);
        }

        // 2. Search PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return Optional.empty();
        for (String dir : pathEnv.split(System.getProperty("path.separator", ":"))) {
            var candidate = Path.of(dir, executable(name));
            if (Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    public static boolean isAvailable(String name) {
        return find(name).isPresent();
    }

    private static String executable(String name) {
        return WINDOWS ? name + ".cmd" : name;
    }

    private static String envDir(String... vars) {
        for (String var : vars) {
            String val = System.getenv(var);
            if (val != null && !val.isBlank()) return val;
        }
        return null;
    }
}
