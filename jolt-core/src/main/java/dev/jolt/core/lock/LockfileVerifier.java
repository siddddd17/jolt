package dev.jolt.core.lock;

import dev.jolt.core.graph.DependencyGraph;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Verifies a resolved {@link DependencyGraph} against an on-disk {@code jolt.lock}.
 *
 * <p>Exit-code contract: returns {@code 4} if any violation is found, {@code 0} otherwise.
 */
public final class LockfileVerifier {

    private LockfileVerifier() {}

    /**
     * @param lockFile path to {@code jolt.lock}
     * @param graph    freshly-resolved dependency graph
     * @param err      where to write violation messages
     * @return 0 if clean, 4 if any mismatch (missing, added, or checksum drift)
     */
    public static int verify(Path lockFile, DependencyGraph graph, PrintStream err) {
        var lockfileOpt = LockfileReader.readIfPresent(lockFile);
        if (lockfileOpt.isEmpty()) {
            err.println("[jolt] --locked: jolt.lock not found at " + lockFile);
            err.println("       Run without --locked to generate it first.");
            return 4;
        }
        var lockfile = lockfileOpt.get();

        // Index locked entries by coordinate
        var locked = new HashMap<String, LockEntry>();
        for (var e : lockfile.packages()) locked.put(e.coordinate(), e);

        // Index resolved nodes by coordinate string (group:artifact:version)
        var resolved = new HashMap<String, dev.jolt.core.graph.DependencyNode>();
        for (var node : graph.allNodes().values()) {
            resolved.put(node.coordinate().toString(), node);
        }

        var violations = new ArrayList<String>();

        // Check every locked entry still matches.
        for (var entry : lockfile.packages()) {
            var node = resolved.get(entry.coordinate());
            if (node == null) {
                violations.add("REMOVED  " + entry.coordinate()
                        + "  (in lock, absent from resolved graph)");
                continue;
            }
            // SHA-256 must match when both sides have a non-blank value.
            if (!entry.sha256().isBlank() && node.checksumSha256() != null
                    && !entry.sha256().equals(node.checksumSha256())) {
                violations.add("CHECKSUM " + entry.coordinate()
                        + "\n         locked:   " + entry.sha256()
                        + "\n         resolved: " + node.checksumSha256());
            }
        }

        // Check for newly appeared artifacts not in lock.
        for (var coord : resolved.keySet()) {
            if (!locked.containsKey(coord)) {
                violations.add("ADDED    " + coord
                        + "  (in resolved graph, absent from lock)");
            }
        }

        if (!violations.isEmpty()) {
            err.println("[jolt] --locked: lock integrity check FAILED");
            for (var v : violations) err.println("  " + v);
            err.println("  Resolve: run 'jolt deps add' (without --locked) to refresh jolt.lock.");
            return 4;
        }
        return 0;
    }

    /** Convenience overload writing to {@link System#err}. */
    public static int verify(Path lockFile, DependencyGraph graph) {
        return verify(lockFile, graph, System.err);
    }
}
