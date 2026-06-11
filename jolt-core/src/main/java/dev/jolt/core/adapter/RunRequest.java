package dev.jolt.core.adapter;

import java.util.List;

public record RunRequest(
        String mainClass,
        List<String> programArgs,
        List<String> jvmArgs,
        List<String> profiles,
        boolean skipBuild) {

    public static RunRequest detect() {
        return new RunRequest(null, List.of(), List.of(), List.of(), false);
    }

    public static RunRequest forClass(String mainClass, List<String> programArgs) {
        return new RunRequest(mainClass, List.copyOf(programArgs), List.of(), List.of(), false);
    }
}
