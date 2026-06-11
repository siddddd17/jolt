package dev.jolt.core.project;

import dev.jolt.core.graph.DependencyGraph;
import dev.jolt.core.graph.GAV;

import java.nio.file.Path;
import java.util.Set;

/**
 * One module inside a multi-module reactor.
 *
 * @param coordinates       the module's own GAV coordinates
 * @param path              path relative to the reactor root (e.g. {@code Path.of("service")})
 * @param moduleDependencies GAVs of sibling modules this module declares as dependencies
 * @param externalGraph     external artifact graph; {@link DependencyGraph#empty()} for a
 *                          lightweight scan (used by {@code --affected} and {@code info})
 */
public record ReactorModule(
        GAV coordinates,
        Path path,
        Set<GAV> moduleDependencies,
        DependencyGraph externalGraph
) {}
