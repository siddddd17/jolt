package dev.jolt.core.graph;

import java.util.List;
import java.util.Map;

public final class DependencyGraph {

    private final List<DependencyNode> roots;
    private final Map<GA, DependencyNode> allNodes;
    private final List<Conflict> conflicts;

    public DependencyGraph(
            List<DependencyNode> roots,
            Map<GA, DependencyNode> allNodes,
            List<Conflict> conflicts) {
        this.roots = List.copyOf(roots);
        this.allNodes = Map.copyOf(allNodes);
        this.conflicts = List.copyOf(conflicts);
    }

    public static DependencyGraph empty() {
        return new DependencyGraph(List.of(), Map.of(), List.of());
    }

    public List<DependencyNode> roots() { return roots; }
    public Map<GA, DependencyNode> allNodes() { return allNodes; }
    public List<Conflict> conflicts() { return conflicts; }

    public int size() { return allNodes.size(); }

    public boolean hasConflicts() { return !conflicts.isEmpty(); }
}
