package dev.jolt.core.lock;

import dev.jolt.core.graph.DependencyGraph;
import dev.jolt.core.graph.DependencyNode;
import dev.jolt.core.project.ReactorGraph;
import dev.jolt.core.project.ReactorModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes {@code jolt.lock} in a deterministic TOML-style format.
 *
 * <p>Determinism rules (acceptance criteria):
 * <ul>
 *   <li>Packages sorted lexicographically by {@code group:artifact:version}.</li>
 *   <li>Multi-module: modules sorted by path (lexicographic); packages within each
 *       module sorted by {@code groupId:artifactId}.</li>
 *   <li>Line endings: LF ({@code \n}) — never CRLF.</li>
 *   <li>Encoding: UTF-8.</li>
 *   <li>No trailing whitespace on any line.</li>
 *   <li>Single trailing newline at end of file.</li>
 * </ul>
 */
public final class LockfileWriter {

    private LockfileWriter() {}

    /** Build a single-module {@link Lockfile} from a resolved graph. */
    public static Lockfile fromGraph(DependencyGraph graph, String projectArtifactId) {
        var packages = new ArrayList<LockEntry>();
        for (var node : graph.allNodes().values()) {
            var parents = node.parents().stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
            packages.add(new LockEntry(
                    node.coordinate().toString(),
                    node.scope().name().toLowerCase(Locale.ROOT),
                    node.repository() != null ? node.repository() : "",
                    node.checksumSha256() != null ? node.checksumSha256() : "",
                    node.pomChecksumSha256() != null ? node.pomChecksumSha256() : "",
                    List.of(projectArtifactId),
                    parents,
                    ""));  // single-module: module field is empty
        }
        packages.sort(Comparator.comparing(LockEntry::coordinate));

        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jolt 0.1.0-SNAPSHOT",
                Instant.now(),
                ".",
                "maven",
                List.copyOf(packages));
    }

    /**
     * Build a multi-module {@link Lockfile} from a fully-resolved {@link ReactorGraph}.
     *
     * <p>Modules are ordered lexicographically by path; packages within each module are
     * ordered by {@code groupId:artifactId} — both orderings are required for cross-OS
     * byte-identical output.
     */
    public static Lockfile fromReactorGraph(ReactorGraph reactor) {
        var sortedModules = new ArrayList<>(reactor.modules());
        sortedModules.sort(Comparator.comparing(m -> m.path().toString()));

        var packages = new ArrayList<LockEntry>();
        for (var module : sortedModules) {
            String moduleName = module.coordinates().artifactId();
            var entries = new ArrayList<LockEntry>();
            for (var node : module.externalGraph().allNodes().values()) {
                var parents = node.parents().stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
                entries.add(new LockEntry(
                        node.coordinate().toString(),
                        node.scope().name().toLowerCase(Locale.ROOT),
                        node.repository() != null ? node.repository() : "",
                        node.checksumSha256() != null ? node.checksumSha256() : "",
                        node.pomChecksumSha256() != null ? node.pomChecksumSha256() : "",
                        List.of(moduleName),
                        parents,
                        moduleName));
            }
            // Sort by groupId:artifactId (coordinate minus the trailing :version)
            entries.sort(Comparator.comparing(e -> gaOf(e.coordinate())));
            packages.addAll(entries);
        }

        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jolt 0.1.0-SNAPSHOT",
                Instant.now(),
                ".",
                "maven",
                List.copyOf(packages));
    }

    /** Serialises {@code lockfile} to {@code target} using LF/UTF-8, no trailing whitespace. */
    public static void write(Lockfile lockfile, Path target) throws IOException {
        var sb = new StringBuilder(4096);
        line(sb, "# jolt.lock \u2014 generated, do not edit by hand");
        line(sb, "version = " + lockfile.version());
        line(sb, "generated_with = \"" + lockfile.generatedWith() + "\"");
        line(sb, "");
        line(sb, "[meta]");
        line(sb, "root = \"" + lockfile.root() + "\"");
        line(sb, "build_system = \"" + lockfile.buildSystem() + "\"");

        String currentModule = null;
        for (var pkg : lockfile.packages()) {
            // Emit [[module]] header when the module name transitions (multi-module only)
            String pkgModule = pkg.module() != null ? pkg.module() : "";
            if (!pkgModule.isEmpty() && !pkgModule.equals(currentModule)) {
                currentModule = pkgModule;
                line(sb, "");
                line(sb, "[[module]]");
                line(sb, "name = \"" + pkgModule + "\"");
            }

            line(sb, "");
            line(sb, "[[package]]");
            line(sb, "coordinate   = \"" + pkg.coordinate() + "\"");
            line(sb, "scope        = \"" + pkg.scope() + "\"");
            line(sb, "repository   = \"" + pkg.repository() + "\"");
            line(sb, "sha256       = \"" + pkg.sha256() + "\"");
            line(sb, "pom_sha256   = \"" + pkg.pomSha256() + "\"");
            line(sb, "requested_by = " + toTomlStringArray(pkg.requestedBy()));
            line(sb, "parents      = " + toTomlStringArray(pkg.parents()));
            if (!pkgModule.isEmpty()) {
                line(sb, "module       = \"" + pkgModule + "\"");
            }
        }

        String content = sb.toString();
        content = content.stripTrailing() + "\n";

        Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String gaOf(String coordinate) {
        int last = coordinate.lastIndexOf(':');
        return last >= 0 ? coordinate.substring(0, last) : coordinate;
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(text.stripTrailing()).append('\n');
    }

    private static String toTomlStringArray(List<String> items) {
        if (items.isEmpty()) return "[]";
        return "[" + items.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", ")) + "]";
    }
}
