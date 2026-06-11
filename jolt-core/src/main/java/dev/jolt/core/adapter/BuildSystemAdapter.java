package dev.jolt.core.adapter;

import dev.jolt.core.graph.DependencyGraph;
import java.nio.file.Path;

public interface BuildSystemAdapter {

    /** Returns true if this adapter handles the project at the given root. */
    boolean detect(Path projectRoot);

    /** Resolves the dependency graph as structured data. Never parses console output. */
    DependencyGraph resolve(Path projectRoot);

    /** Runs tests. Returns exit code. */
    int test(Path projectRoot, TestRequest req);

    /** Runs a main class or entry point. Returns exit code. */
    int run(Path projectRoot, RunRequest req);

    /** Cleans build outputs. Returns exit code. */
    int clean(Path projectRoot);
}
