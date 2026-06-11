package dev.jolt.core.project;

import dev.jolt.core.graph.GAV;

import java.nio.file.Path;
import java.util.*;

/**
 * Represents the inter-module dependency graph of a multi-module reactor.
 *
 * <p>{@link #modules()} is in topological build order (dependencies before dependents).
 * {@link #reverseDependents(GAV)} answers "which modules depend on X" in O(1).
 */
public record ReactorGraph(
        Path root,
        List<ReactorModule> modules,
        Map<GAV, Set<GAV>> reverseDependents
) {
    public Optional<ReactorModule> findByPath(Path relPath) {
        return modules.stream().filter(m -> m.path().equals(relPath)).findFirst();
    }

    public Optional<ReactorModule> findByGAV(GAV gav) {
        return modules.stream().filter(m -> m.coordinates().equals(gav)).findFirst();
    }

    /** Returns the set of module GAVs that declare a dependency on {@code gav}. */
    public Set<GAV> reverseDependents(GAV gav) {
        return reverseDependents.getOrDefault(gav, Set.of());
    }
}
