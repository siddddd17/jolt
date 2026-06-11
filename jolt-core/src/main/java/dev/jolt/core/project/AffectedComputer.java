package dev.jolt.core.project;

import dev.jolt.core.graph.GAV;
import dev.jolt.core.util.ProcessRunner;

import java.nio.file.Path;
import java.util.*;

/**
 * Computes the set of reactor modules affected by changes since a given git ref.
 *
 * <p>Base-ref precedence (matches Nx / Turborepo / Bazel CI conventions):
 * <ol>
 *   <li>{@code --since} flag if explicitly provided</li>
 *   <li>{@code GITHUB_BASE_REF} env var (PR context in GitHub Actions):
 *       resolved as {@code origin/$GITHUB_BASE_REF}</li>
 *   <li>Otherwise: {@code HEAD~1}</li>
 * </ol>
 */
public final class AffectedComputer {

    private AffectedComputer() {}

    /** Resolves the git base ref to diff against using the documented precedence. */
    public static String resolveBaseRef(String sinceOpt) {
        if (sinceOpt != null && !sinceOpt.isBlank()) return sinceOpt;
        String ghBase = System.getenv("GITHUB_BASE_REF");
        if (ghBase != null && !ghBase.isBlank()) return "origin/" + ghBase;
        return "HEAD~1";
    }

    /**
     * Returns affected modules in topological order.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Run {@code git diff --name-only --relative <baseRef>} from the reactor root.
     *       {@code --relative} makes all paths reactor-root-relative regardless of where
     *       the user invoked jolt from.</li>
     *   <li>Map each changed path to its owning module (longest-prefix match).</li>
     *   <li>BFS on {@link ReactorGraph#reverseDependents} to find all transitive dependents.</li>
     *   <li>Return the union filtered through {@link ReactorGraph#modules()} to preserve
     *       topological order.</li>
     * </ol>
     *
     * <p>If {@code git diff} fails (e.g. shallow clone with only one commit), all modules
     * are returned so callers fail-open and test everything.
     */
    public static List<ReactorModule> compute(ReactorGraph reactor, String baseRef) {
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.capture(
                    reactor.root(), "git", "diff", "--name-only", "--relative", baseRef);
        } catch (Exception e) {
            return List.copyOf(reactor.modules());
        }

        if (!result.succeeded()) {
            return List.copyOf(reactor.modules());
        }

        // Map changed files to their owning modules
        var seedGavs = new LinkedHashSet<GAV>();
        for (String line : result.output().lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            ReactorModule owner = findOwningModule(reactor, Path.of(trimmed));
            if (owner != null) seedGavs.add(owner.coordinates());
        }

        if (seedGavs.isEmpty()) return List.of();

        // BFS on reverse-dependency edges to collect transitively affected modules
        var visited = new LinkedHashSet<>(seedGavs);
        var queue   = new ArrayDeque<>(seedGavs);
        while (!queue.isEmpty()) {
            GAV current = queue.poll();
            for (GAV dependent : reactor.reverseDependents(current)) {
                if (visited.add(dependent)) queue.add(dependent);
            }
        }

        // Return in topological build order
        return reactor.modules().stream()
                .filter(m -> visited.contains(m.coordinates()))
                .toList();
    }

    private static ReactorModule findOwningModule(ReactorGraph reactor, Path changed) {
        ReactorModule best   = null;
        int           bestLen = -1;
        for (var module : reactor.modules()) {
            Path mp = module.path();
            String mpStr = mp.toString();
            if (mpStr.equals(".") || mpStr.isEmpty()) {
                if (bestLen < 0) { best = module; bestLen = 0; }
                continue;
            }
            if (changed.startsWith(mp)) {
                int len = mp.getNameCount();
                if (len > bestLen) { best = module; bestLen = len; }
            }
        }
        return best;
    }
}
