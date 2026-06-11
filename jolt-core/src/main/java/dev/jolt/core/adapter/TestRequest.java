package dev.jolt.core.adapter;

import java.util.List;

public record TestRequest(
        String testPattern,
        String methodPattern,
        boolean failFast,
        List<String> profiles,
        boolean json) {

    public static TestRequest all() {
        return new TestRequest(null, null, false, List.of(), false);
    }

    public static TestRequest forClass(String className) {
        return new TestRequest(className, null, false, List.of(), false);
    }

    public static TestRequest forMethod(String className, String method) {
        return new TestRequest(className, method, false, List.of(), false);
    }
}
