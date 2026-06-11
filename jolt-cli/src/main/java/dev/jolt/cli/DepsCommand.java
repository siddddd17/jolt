package dev.jolt.cli;

import dev.jolt.adapter.maven.GraphResolver;
import dev.jolt.core.graph.GA;
import dev.jolt.core.graph.GAV;
import dev.jolt.core.graph.Scope;
import dev.jolt.core.lock.LockfileVerifier;
import dev.jolt.core.lock.LockfileWriter;
import dev.jolt.core.project.BuildSystem;
import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import org.eclipse.aether.graph.DependencyNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command(name = "deps",
         description = "Dependency management: search, add, remove, list, tree, why, outdated, audit.",
         subcommands = {
             DepsCommand.SearchCommand.class,
             DepsCommand.AddCommand.class,
             DepsCommand.RemoveCommand.class,
             DepsCommand.ListCommand.class,
             DepsCommand.WhyCommand.class,
             DepsCommand.OutdatedCommand.class,
             DepsCommand.AuditCommand.class
         })
final class DepsCommand implements Callable<Integer> {

    @ParentCommand
    Jolt parent;

    @Override
    public Integer call() {
        new Output(parent.quiet, parent.verbose, parent.json, parent.noColor)
            .info("Usage: jolt deps <subcommand>  (search | add | remove | list | why | outdated | audit)");
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // search
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "search",
             description = "Search Maven Central for artifacts. Results cached 60 s.")
    static final class SearchCommand implements Callable<Integer> {
        @ParentCommand DepsCommand deps;
        @Parameters(index = "0", paramLabel = "<term>") String term;

        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            try {
                var results = search(term, out);
                if (results.isEmpty()) {
                    out.info("No results found for '" + term + "'.");
                    return 0;
                }
                out.info("Top results for \"" + term + "\":\n");
                for (var r : results) {
                    out.info("  " + r.groupId() + ":" + r.artifactId()
                            + "  " + r.latestVersion());
                    if (r.description() != null && !r.description().isBlank()) {
                        out.verbose("    " + r.description());
                    }
                }
            } catch (Exception e) {
                out.fail("Search failed: " + e.getMessage());
                if (deps.parent.verbose) e.printStackTrace();
                return 1;
            }
            return 0;
        }

        private List<SearchResult> search(String query, Output out) throws Exception {
            // ── Cache (60 s TTL) ─────────────────────────────────────────────
            Path cacheDir = Path.of(System.getProperty("user.home"), ".jolt", "cache", "search");
            Files.createDirectories(cacheDir);
            String cacheKey = query.replaceAll("[^\\w.-]", "_") + ".json";
            Path cacheFile  = cacheDir.resolve(cacheKey);

            if (Files.exists(cacheFile)) {
                long ageMs = System.currentTimeMillis()
                        - Files.getLastModifiedTime(cacheFile).toMillis();
                if (ageMs < 60_000) {
                    out.verbose("(search results from cache)");
                    return parseResponse(Files.readString(cacheFile, StandardCharsets.UTF_8));
                }
            }

            // ── Fetch from Maven Central Solr API ────────────────────────────
            String url = "https://search.maven.org/solrsearch/select?q="
                    + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&rows=5&wt=json";
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "jolt-cli/0.1")
                    .GET().build();
            var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Maven Central returned HTTP " + response.statusCode());
            }

            String json = response.body();
            Files.writeString(cacheFile, json, StandardCharsets.UTF_8);
            return parseResponse(json);
        }

        /**
         * Parse the Solr JSON response.  Uses balanced-brace splitting to handle each
         * doc object robustly without pulling in a JSON library.
         */
        private List<SearchResult> parseResponse(String json) {
            var results = new ArrayList<SearchResult>();

            int docsIdx = json.indexOf("\"docs\":[");
            if (docsIdx < 0) return results;
            int start = docsIdx + "\"docs\":[".length();
            int end   = json.lastIndexOf(']', json.lastIndexOf(']') - 1); // outer ]
            if (end <= start) end = json.length() - 1;

            // Walk balanced braces to extract individual doc objects.
            int depth = 0, objStart = -1;
            for (int i = start; i < end && i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') { if (depth++ == 0) objStart = i; }
                else if (c == '}' && --depth == 0 && objStart >= 0) {
                    String doc = json.substring(objStart, i + 1);
                    var gm = Pattern.compile("\"g\":\"([^\"]+)\"").matcher(doc);
                    var am = Pattern.compile("\"a\":\"([^\"]+)\"").matcher(doc);
                    var vm = Pattern.compile("\"latestVersion\":\"([^\"]+)\"").matcher(doc);
                    if (gm.find() && am.find() && vm.find()) {
                        results.add(new SearchResult(gm.group(1), am.group(1), vm.group(1), null));
                    }
                    objStart = -1;
                }
            }
            return results;
        }

        record SearchResult(String groupId, String artifactId,
                            String latestVersion, String description) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // add
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "add",
             description = "Add a dependency to pom.xml (format-preserving, BOM-aware) and refresh jolt.lock.")
    static final class AddCommand implements Callable<Integer> {

        @ParentCommand DepsCommand deps;

        @Parameters(index = "0", paramLabel = "<coords>",
                    description = "Coordinates: groupId:artifactId or groupId:artifactId:version.")
        String coords;

        @Option(names = "--scope", paramLabel = "<scope>",
                description = "Dependency scope (default: compile).")
        String scope;

        @Option(names = "--no-resolve", description = "Skip post-add resolution and lock update.")
        boolean noResolve;

        @Option(names = "--optional", description = "Mark as optional.")
        boolean optional;

        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            Path cwd = deps.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
            var project = projectOpt.get();

            String[] parts = coords.split(":");
            if (parts.length < 2) {
                out.fail("Invalid coordinates '" + coords
                        + "'. Expected groupId:artifactId[:version].");
                return 2;
            }
            String groupId    = parts[0];
            String artifactId = parts[1];
            String version    = parts.length >= 3 ? parts[2] : null;

            if (project.buildSystem() == BuildSystem.GRADLE) {
                try {
                    new dev.jolt.adapter.gradle.GradleAdapter()
                            .addDependency(project.root().resolve("build.gradle"),
                                    groupId, artifactId, version,
                                    scope != null ? scope : "compile");
                    out.success("Added " + groupId + ":" + artifactId
                            + (version != null ? ":" + version : ""));
                    out.warn("jolt.lock refresh not supported for Gradle — run 'jolt deps list' to verify.");
                } catch (Exception e) {
                    out.fail("deps add failed: " + e.getMessage());
                    if (deps.parent.verbose) e.printStackTrace();
                    return 1;
                }
                return 0;
            }

            if (project.buildSystem() != BuildSystem.MAVEN) {
                out.fail("deps add currently supports Maven and Gradle projects only."); return 3;
            }

            Path pomFile = project.root().resolve("pom.xml");

            try {
                String existing = Files.readString(pomFile, StandardCharsets.UTF_8);
                if (existing.contains("<artifactId>" + artifactId + "</artifactId>")) {
                    out.warn(artifactId + " is already declared in pom.xml — skipping add.");
                    return 0;
                }

                insertDependency(pomFile, groupId, artifactId, version, scope, optional);
                out.success("Added " + groupId + ":" + artifactId
                    + (version != null ? ":" + version : " (version managed by BOM)"));

                if (!noResolve) {
                    out.verbose("Resolving dependency graph and refreshing jolt.lock…");
                    try {
                        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
                        var resolver = new GraphResolver();
                        var session  = resolver.newSession(localRepo);
                        var result   = resolver.resolve(project.root(), session,
                                                        List.of(GraphResolver.CENTRAL));
                        var lockfile = LockfileWriter.fromGraph(result.graph(),
                                                                project.artifactId());
                        Path lockPath = project.root().resolve("jolt.lock");
                        LockfileWriter.write(lockfile, lockPath);
                        out.success("jolt.lock refreshed (" + result.graph().size()
                                    + " artifacts)");
                    } catch (Exception e) {
                        out.warn("Resolution/lock update failed: " + e.getMessage());
                        if (deps.parent.verbose) e.printStackTrace();
                        return 1;
                    }
                }
                return 0;
            } catch (Exception e) {
                out.fail("deps add failed: " + e.getMessage());
                if (deps.parent.verbose) e.printStackTrace();
                return 1;
            }
        }

        /**
         * Inserts a {@code <dependency>} element just before the last
         * {@code </dependencies>} tag, preserving the file's existing indentation.
         */
        static void insertDependency(Path pomFile, String groupId, String artifactId,
                                     String version, String scope, boolean optional)
                throws IOException {
            String content = Files.readString(pomFile, StandardCharsets.UTF_8);

            // Find the last </dependencies> closing tag.
            int closingTagPos = content.lastIndexOf("</dependencies>");
            if (closingTagPos < 0) {
                throw new IOException("No </dependencies> section found in " + pomFile);
            }

            // Insert before the entire line that contains </dependencies>, preserving
            // that line's own indentation.  This avoids the off-by-indent bug that
            // occurs when inserting at closingTagPos (which skips past the leading
            // whitespace that belongs to </dependencies>).
            int insertAt = content.lastIndexOf('\n', closingTagPos) + 1;

            // Infer indentation from </dependencies> line: whatever whitespace precedes
            // the closing tag is the section indent; <dependency> is one level deeper.
            // Using the closing tag is reliable even when <dependencyManagement> also
            // contains <dependency> elements at a different indent level.
            String sectionIndent = content.substring(insertAt, closingTagPos);
            String depIndent   = sectionIndent + "  ";
            String childIndent = sectionIndent + "    ";

            var sb = new StringBuilder();
            sb.append(depIndent).append("<dependency>\n");
            sb.append(childIndent).append("<groupId>").append(groupId).append("</groupId>\n");
            sb.append(childIndent).append("<artifactId>").append(artifactId).append("</artifactId>\n");
            if (version != null) {
                sb.append(childIndent).append("<version>").append(version).append("</version>\n");
            }
            if (scope != null && !"compile".equals(scope)) {
                sb.append(childIndent).append("<scope>").append(scope).append("</scope>\n");
            }
            if (optional) {
                sb.append(childIndent).append("<optional>true</optional>\n");
            }
            sb.append(depIndent).append("</dependency>\n");

            Files.writeString(pomFile,
                    content.substring(0, insertAt) + sb + content.substring(insertAt),
                    StandardCharsets.UTF_8);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // remove
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "remove", description = "Remove a dependency from pom.xml and re-lock.")
    static final class RemoveCommand implements Callable<Integer> {
        @ParentCommand DepsCommand deps;
        @Parameters(index = "0", paramLabel = "<coords>",
                    description = "groupId:artifactId or groupId:artifactId:version")
        String coords;

        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            Path cwd = deps.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
            var project = projectOpt.get();

            String[] parts = coords.split(":");
            if (parts.length < 2) {
                out.fail("Invalid coordinates '" + coords
                        + "'. Expected groupId:artifactId[:version].");
                return 2;
            }
            String groupId    = parts[0];
            String artifactId = parts[1];

            if (project.buildSystem() == BuildSystem.GRADLE) {
                try {
                    new dev.jolt.adapter.gradle.GradleAdapter()
                            .removeDependency(project.root().resolve("build.gradle"), groupId, artifactId);
                    out.success("Removed " + groupId + ":" + artifactId);
                    out.warn("jolt.lock refresh not supported for Gradle — run 'jolt deps list' to verify.");
                } catch (Exception e) {
                    out.fail("deps remove failed: " + e.getMessage());
                    if (deps.parent.verbose) e.printStackTrace();
                    return 1;
                }
                return 0;
            }

            if (project.buildSystem() != BuildSystem.MAVEN) {
                out.fail("deps remove currently supports Maven and Gradle projects only."); return 3;
            }

            Path pomFile = project.root().resolve("pom.xml");

            try {
                String content = Files.readString(pomFile, StandardCharsets.UTF_8);
                if (!content.contains("<artifactId>" + artifactId + "</artifactId>")) {
                    out.warn(artifactId + " is not declared in pom.xml — nothing to remove.");
                    return 0;
                }

                String updated = removeDependency(content, groupId, artifactId);
                if (updated == null) {
                    out.fail(groupId + ":" + artifactId
                            + " not found in <dependencies> section of " + pomFile);
                    return 1;
                }

                Files.writeString(pomFile, updated, StandardCharsets.UTF_8);
                out.success("Removed " + groupId + ":" + artifactId);

                out.verbose("Re-resolving dependency graph and refreshing jolt.lock…");
                try {
                    Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
                    var resolver = new GraphResolver();
                    var session  = resolver.newSession(localRepo);
                    var result   = resolver.resolve(project.root(), session,
                                                    List.of(GraphResolver.CENTRAL));
                    var lockfile = LockfileWriter.fromGraph(result.graph(), project.artifactId());
                    Path lockPath = project.root().resolve("jolt.lock");
                    LockfileWriter.write(lockfile, lockPath);
                    out.success("jolt.lock refreshed (" + result.graph().size() + " artifacts)");
                } catch (Exception e) {
                    out.warn("Resolution/lock update failed: " + e.getMessage());
                    if (deps.parent.verbose) e.printStackTrace();
                    return 1;
                }
                return 0;
            } catch (Exception e) {
                out.fail("deps remove failed: " + e.getMessage());
                if (deps.parent.verbose) e.printStackTrace();
                return 1;
            }
        }

        /**
         * Remove the {@code <dependency>} block for {@code groupId:artifactId} from
         * {@code content}, preserving all surrounding comments and whitespace.
         *
         * <p>Strategy: locate the {@code <artifactId>} tag, walk backward to the
         * enclosing {@code <dependency>} open-tag, verify the block also contains the
         * right {@code <groupId>}, then excise the block along with its preceding
         * blank-only line so no empty line is left behind.
         *
         * <p>The block is excluded from {@code <dependencyManagement>} to avoid
         * accidentally removing a BOM import or managed version entry.
         *
         * @return the modified content, or {@code null} if the dependency was not found
         */
        static String removeDependency(String content, String groupId, String artifactId) {
            String gTag    = "<groupId>" + groupId + "</groupId>";
            String aTag    = "<artifactId>" + artifactId + "</artifactId>";
            String depOpen = "<dependency>";
            String depClose = "</dependency>";

            // Locate <dependencyManagement> range so we skip it.
            int mgmtStart = content.indexOf("<dependencyManagement>");
            int mgmtEnd   = mgmtStart >= 0
                    ? content.indexOf("</dependencyManagement>", mgmtStart) : -1;

            int searchFrom = 0;
            while (true) {
                int aTagPos = content.indexOf(aTag, searchFrom);
                if (aTagPos < 0) return null;

                // Skip occurrences inside <dependencyManagement>.
                if (mgmtStart >= 0 && mgmtEnd >= 0
                        && aTagPos > mgmtStart && aTagPos < mgmtEnd) {
                    searchFrom = aTagPos + 1;
                    continue;
                }

                int blockStart = content.lastIndexOf(depOpen, aTagPos);
                int blockEnd   = content.indexOf(depClose, aTagPos);
                if (blockStart < 0 || blockEnd < 0) { searchFrom = aTagPos + 1; continue; }

                // Make sure no other open-tag appears between blockStart and aTagPos
                // (guards against matching an <artifactId> inside a <plugin> block).
                String between = content.substring(blockStart + depOpen.length(), aTagPos);
                if (between.contains("<plugin>") || between.contains("<parent>")) {
                    searchFrom = aTagPos + 1;
                    continue;
                }

                String block = content.substring(blockStart,
                        blockEnd + depClose.length());
                if (!block.contains(gTag)) { searchFrom = aTagPos + 1; continue; }

                // Determine removal range: include the newline+indent that precedes
                // <dependency> if the entire preceding text on that line is whitespace.
                int lineStart = content.lastIndexOf('\n', blockStart);
                boolean blankLine = lineStart >= 0
                        && content.substring(lineStart + 1, blockStart).isBlank();
                int removeFrom = blankLine ? lineStart : blockStart;
                int removeTo   = blockEnd + depClose.length();

                return content.substring(0, Math.max(0, removeFrom))
                        + content.substring(removeTo);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // list
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "list", description = "List declared or resolved dependencies.")
    static final class ListCommand implements Callable<Integer> {

        @ParentCommand DepsCommand deps;

        @Option(names = "--tree", description = "Render the full resolved dependency tree.")
        boolean tree;

        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            Path cwd = deps.parent.effectiveCwd();
            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
            var project = projectOpt.get();

            Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            var resolver = new GraphResolver();
            var session  = resolver.newSession(localRepo);
            var result   = resolver.resolve(project.root(), session, List.of(GraphResolver.CENTRAL));

            // --locked: verify before displaying
            if (deps.parent.locked) {
                int code = LockfileVerifier.verify(project.root().resolve("jolt.lock"),
                                                   result.graph());
                if (code != 0) return code;
            }

            if (tree) {
                out.info(project.artifactId() + " (resolved dependency tree)");
                printTree(result.aetherRoot().getChildren(), "", result.widestScopes(), out);
            } else {
                out.info("Direct dependencies (resolved):");
                for (var node : result.graph().allNodes().values()) {
                    if (node.parents().isEmpty()) {
                        out.info("  " + node.coordinate() + " [" + node.scope().name().toLowerCase() + "]");
                    }
                }
                out.info("\nTotal: " + result.graph().size() + " artifacts");
            }
            return 0;
        }

        private void printTree(List<DependencyNode> nodes, String prefix,
                               Map<GA, String> widestScopes, Output out) {
            for (int i = 0; i < nodes.size(); i++) {
                boolean last = (i == nodes.size() - 1);
                var node = nodes.get(i);
                var dep  = node.getDependency();
                var art  = node.getArtifact();
                if (dep == null || art == null) continue;

                var ga = new GA(art.getGroupId(), art.getArtifactId());
                String effectiveScope = widestScopes.getOrDefault(ga,
                        dep.getScope() != null ? dep.getScope() : "compile");

                String connector  = last ? "\u2514\u2500 " : "\u251C\u2500 ";
                String childPfx   = prefix + (last ? "   " : "\u2502  ");
                out.info(prefix + connector
                        + art.getGroupId() + ":" + art.getArtifactId()
                        + ":" + art.getVersion()
                        + " [" + effectiveScope + "]");

                if (!node.getChildren().isEmpty()) {
                    printTree(node.getChildren(), childPfx, widestScopes, out);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // why
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "why", description = "Explain why a transitive dependency is present.")
    static final class WhyCommand implements Callable<Integer> {

        @ParentCommand DepsCommand deps;

        @Parameters(index = "0", paramLabel = "<coords>",
                    description = "groupId:artifactId or groupId:artifactId:version")
        String coords;

        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            Path cwd = deps.parent.effectiveCwd();
            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
            var project = projectOpt.get();

            Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            var resolver = new GraphResolver();
            var session  = resolver.newSession(localRepo);
            var result   = resolver.resolve(project.root(), session, List.of(GraphResolver.CENTRAL));
            var graph    = result.graph();

            // Accept group:artifact or group:artifact:version
            String[] parts = coords.split(":");
            if (parts.length < 2) {
                out.fail("Expected groupId:artifactId[:version]"); return 2;
            }
            var ga   = new GA(parts[0], parts[1]);
            var node = graph.allNodes().get(ga);
            if (node == null) {
                out.fail(coords + " is not in the resolved dependency graph.");
                return 1;
            }

            out.info(node.coordinate() + " [" + node.scope().name().toLowerCase() + "]");
            if (node.parents().isEmpty()) {
                out.info("  → (direct dependency of " + project.artifactId() + ")");
            } else {
                out.info("  is pulled in transitively:");
                printWhyChain(node.coordinate().ga(), graph.allNodes(), "  ", out);
            }
            return 0;
        }

        private void printWhyChain(
                GA target,
                java.util.Map<GA, dev.jolt.core.graph.DependencyNode> allNodes,
                String indent,
                Output out) {
            var node = allNodes.get(target);
            if (node == null) return;
            for (var parent : node.parents()) {
                var pNode = allNodes.get(parent.ga());
                boolean isDirect = (pNode == null || pNode.parents().isEmpty());
                out.info(indent + "\u2192 " + parent
                        + (isDirect ? "  (direct)" : ""));
                if (!isDirect) {
                    printWhyChain(parent.ga(), allNodes, indent + "   ", out);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // outdated
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "outdated", description = "Check for newer versions of declared dependencies.")
    static final class OutdatedCommand implements Callable<Integer> {

        @ParentCommand DepsCommand deps;

        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            Path cwd = deps.parent.effectiveCwd();
            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
            var project = projectOpt.get();

            Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            var resolver = new GraphResolver();
            var session  = resolver.newSession(localRepo);
            var result   = resolver.resolve(project.root(), session, List.of(GraphResolver.CENTRAL));

            out.info("Checking for updates (direct dependencies only)…\n");
            boolean anyOutdated = false;
            for (var node : result.graph().allNodes().values()) {
                if (!node.parents().isEmpty()) continue; // skip transitives
                String latest = queryLatest(node.coordinate().groupId(),
                                            node.coordinate().artifactId(), out);
                if (latest == null) continue;
                if (!latest.equals(node.selectedVersion())) {
                    out.info("  " + node.coordinate().ga()
                            + "  " + node.selectedVersion()
                            + "  →  " + latest);
                    anyOutdated = true;
                }
            }
            if (!anyOutdated) out.success("All direct dependencies are up to date.");
            return 0;
        }

        private String queryLatest(String groupId, String artifactId, Output out) {
            try {
                var url = "https://search.maven.org/solrsearch/select?q=g:"
                        + groupId + "+AND+a:" + artifactId + "&rows=1&wt=json";
                var conn = new java.net.URL(url).openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                String json = new String(conn.getInputStream().readAllBytes(),
                                         StandardCharsets.UTF_8);
                var m = Pattern.compile("\"latestVersion\":\"([^\"]+)\"").matcher(json);
                return m.find() ? m.group(1) : null;
            } catch (Exception e) {
                out.verbose("  Could not query Maven Central for "
                        + groupId + ":" + artifactId + ": " + e.getMessage());
                return null;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // audit
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "audit",
             description = "Scan resolved dependency graph for known CVEs via OSV API.")
    static final class AuditCommand implements Callable<Integer> {

        @ParentCommand DepsCommand deps;

        @Option(names = "--fail-on", paramLabel = "<severity>",
                description = "Exit 1 if any finding is at or above LOW|MEDIUM|HIGH|CRITICAL. Default: HIGH.",
                defaultValue = "HIGH")
        String failOn;

        @Option(names = "--no-cache", description = "Bypass 24-hour OSV response cache.")
        boolean noCache;

        // ── Severity ──────────────────────────────────────────────────────────
        enum Severity {
            LOW, MEDIUM, HIGH, CRITICAL;

            static Severity fromScore(double score) {
                if (score >= 9.0) return CRITICAL;
                if (score >= 7.0) return HIGH;
                if (score >= 4.0) return MEDIUM;
                return LOW;
            }

            boolean atOrAbove(Severity threshold) {
                return this.ordinal() >= threshold.ordinal();
            }
        }

        record Finding(String id, String packageCoord, String version,
                       Severity severity, double score, String summary) {}

        // ── Entry point ───────────────────────────────────────────────────────
        @Override
        public Integer call() {
            var out = new Output(deps.parent.quiet, deps.parent.verbose,
                                 deps.parent.json, deps.parent.noColor);
            Path cwd = deps.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
            var project = projectOpt.get();

            Severity threshold;
            try { threshold = Severity.valueOf(failOn.toUpperCase()); }
            catch (IllegalArgumentException e) {
                out.fail("Invalid --fail-on value: " + failOn + " (use LOW|MEDIUM|HIGH|CRITICAL)");
                return 2;
            }

            Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            var resolver = new GraphResolver();
            var session  = resolver.newSession(localRepo);
            var result   = resolver.resolve(project.root(), session, List.of(GraphResolver.CENTRAL));
            var graph    = result.graph();

            out.verbose("Scanning " + graph.size() + " artifacts for known vulnerabilities…");

            var allNodes = new ArrayList<>(graph.allNodes().values());

            // Per-coordinate vulns: coordString -> list of vuln JSON objects
            var vulnsPerCoord = new LinkedHashMap<String, List<String>>();

            Path cacheDir = Path.of(System.getProperty("user.home"), ".jolt", "cache", "audit");
            if (!noCache) {
                try { Files.createDirectories(cacheDir); } catch (IOException ignored) {}
            }

            // Phase 1: load from cache
            var uncached = new ArrayList<dev.jolt.core.graph.DependencyNode>();
            for (var node : allNodes) {
                String coordStr = node.coordinate().toString();
                if (!noCache) {
                    Path cf = cacheDir.resolve(sha256hex(coordStr) + ".json");
                    if (Files.exists(cf)) {
                        try {
                            long age = System.currentTimeMillis()
                                    - Files.getLastModifiedTime(cf).toMillis();
                            if (age < 86_400_000L) {
                                String content = Files.readString(cf, StandardCharsets.UTF_8).strip();
                                if (isValidJsonArray(content)
                                        && isHydratedCache(content)) {
                                    vulnsPerCoord.put(coordStr, extractObjectsFromArray(content));
                                    continue;
                                }
                                Files.deleteIfExists(cf); // corrupted or thin — re-fetch
                            }
                        } catch (IOException ignored) {}
                    }
                }
                uncached.add(node);
            }

            // Phase 2: batch-fetch uncached (500 per batch, 200 ms between batches)
            int BATCH = 500;
            for (int i = 0; i < uncached.size(); i += BATCH) {
                if (i > 0) {
                    try { Thread.sleep(200); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                var batch = uncached.subList(i, Math.min(i + BATCH, uncached.size()));
                try {
                    queryOsv(batch, vulnsPerCoord, cacheDir, out);
                } catch (Exception e) {
                    out.warn("OSV query failed: " + e.getMessage());
                    if (deps.parent.verbose) e.printStackTrace();
                }
            }

            // Phase 3: collect findings
            var findings = new ArrayList<Finding>();
            for (var node : allNodes) {
                String coordStr = node.coordinate().toString();
                for (String vulnJson : vulnsPerCoord.getOrDefault(coordStr, List.of())) {
                    String rawId      = extractString(vulnJson, "id");
                    List<String> aliases = extractStringArray(vulnJson, "aliases");
                    String summary    = extractString(vulnJson, "summary");
                    String displayId  = aliases.stream()
                            .filter(a -> a.startsWith("CVE-")).findFirst()
                            .orElse(rawId != null ? rawId : "UNKNOWN");
                    Severity sev   = parseSeverity(vulnJson);
                    double   score = cvssBaseScore(vulnJson);
                    findings.add(new Finding(
                            displayId,
                            node.coordinate().groupId() + ":" + node.coordinate().artifactId(),
                            node.coordinate().version(),
                            sev, score,
                            summary != null ? summary : ""));
                }
            }

            // Phase 4: output
            boolean fail = findings.stream().anyMatch(f -> f.severity().atOrAbove(threshold));
            if (out.isJson()) {
                printJson(findings, project.artifactId(), out);
            } else {
                printText(findings, project.artifactId(), threshold, out);
            }
            return fail ? 1 : 0;
        }

        // ── OSV fetch ─────────────────────────────────────────────────────────
        private void queryOsv(List<dev.jolt.core.graph.DependencyNode> batch,
                               Map<String, List<String>> vulnsPerCoord,
                               Path cacheDir, Output out) throws Exception {
            // Build querybatch request body
            var sb = new StringBuilder("{\"queries\":[");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) sb.append(',');
                var c = batch.get(i).coordinate();
                sb.append("{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"")
                  .append(ej(c.groupId())).append(':').append(ej(c.artifactId()))
                  .append("\"},\"version\":\"").append(ej(c.version())).append("\"}");
            }
            sb.append("]}");

            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.osv.dev/v1/querybatch"))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "jolt-cli/0.1")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(sb.toString()))
                    .build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                out.warn("OSV API HTTP " + resp.statusCode() + " — skipping batch");
                return;
            }

            String body = resp.body();
            int ri = body.indexOf("\"results\":[");
            if (ri < 0) return;
            int arrayStart = ri + "\"results\":[".length() - 1;
            List<String> resultObjs = extractObjectsFromPos(body, arrayStart);

            // Step 1: collect vuln IDs per coordinate from querybatch (returns only id+modified)
            var coordIds = new java.util.LinkedHashMap<String, List<String>>();
            var allIds   = new java.util.LinkedHashSet<String>();
            for (int i = 0; i < Math.min(resultObjs.size(), batch.size()); i++) {
                String coordStr = batch.get(i).coordinate().toString();
                String resObj   = resultObjs.get(i);
                var ids = new ArrayList<String>();
                int vi = resObj.indexOf("\"vulns\":[");
                if (vi >= 0) {
                    int vs = vi + "\"vulns\":[".length() - 1;
                    for (String mv : extractObjectsFromPos(resObj, vs)) {
                        String id = extractString(mv, "id");
                        if (id != null) { ids.add(id); allIds.add(id); }
                    }
                }
                coordIds.put(coordStr, ids);
            }

            // Step 2: hydrate each unique vuln ID via GET /v1/vulns/{id}
            // querybatch only returns id+modified; full severity/aliases require the detail endpoint.
            Path vulnCacheDir = cacheDir.getParent().resolve("audit-vulns");
            try { Files.createDirectories(vulnCacheDir); } catch (IOException ignored) {}

            var vulnDetails = new HashMap<String, String>(); // id -> full JSON
            for (String id : allIds) {
                Path cf = vulnCacheDir.resolve(id.replace('/', '_') + ".json");
                if (!noCache && Files.exists(cf)) {
                    try {
                        long age = System.currentTimeMillis()
                                - Files.getLastModifiedTime(cf).toMillis();
                        if (age < 86_400_000L) {
                            vulnDetails.put(id, Files.readString(cf, StandardCharsets.UTF_8));
                            continue;
                        }
                    } catch (IOException ignored) {}
                }
                try {
                    Thread.sleep(50);
                    var detailReq = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("https://api.osv.dev/v1/vulns/" + id))
                            .timeout(java.time.Duration.ofSeconds(15))
                            .header("User-Agent", "jolt-cli/0.1")
                            .GET().build();
                    var dr = client.send(detailReq,
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (dr.statusCode() == 200) {
                        String detail = dr.body();
                        vulnDetails.put(id, detail);
                        if (!noCache) {
                            try { Files.writeString(cf, detail, StandardCharsets.UTF_8); }
                            catch (IOException ignored) {}
                        }
                    }
                } catch (Exception e) {
                    out.verbose("Could not fetch details for " + id + ": " + e.getMessage());
                }
            }

            // Step 3: build final vulnsPerCoord with hydrated objects; cache per coordinate
            for (var entry : coordIds.entrySet()) {
                String coordStr = entry.getKey();
                var fullVulns = new ArrayList<String>();
                for (String id : entry.getValue()) {
                    String detail = vulnDetails.get(id);
                    if (detail != null) fullVulns.add(detail);
                }
                vulnsPerCoord.put(coordStr, fullVulns);

                if (!noCache) {
                    try {
                        String cached = fullVulns.isEmpty() ? "[]"
                                : "[" + String.join(",", fullVulns) + "]";
                        Files.writeString(cacheDir.resolve(sha256hex(coordStr) + ".json"),
                                cached, StandardCharsets.UTF_8);
                    } catch (IOException ignored) {}
                }
            }
        }

        // ── Severity parsing ──────────────────────────────────────────────────
        private static Severity parseSeverity(String vulnJson) {
            // CVSS v3 vector is the most reliable source
            var cm = Pattern.compile("CVSS:3[.][0-9]/[\\w:./+]+").matcher(vulnJson);
            if (cm.find()) return Severity.fromScore(cvssV3Score(cm.group()));
            // Fallback: severity string in database_specific
            var sm = Pattern.compile("\"severity\"\\s*:\\s*\"(CRITICAL|HIGH|MEDIUM|LOW)\"",
                    Pattern.CASE_INSENSITIVE).matcher(vulnJson);
            if (sm.find()) {
                try { return Severity.valueOf(sm.group(1).toUpperCase()); }
                catch (IllegalArgumentException ignored) {}
            }
            return Severity.HIGH;
        }

        private static double cvssBaseScore(String vulnJson) {
            var cm = Pattern.compile("CVSS:3[.][0-9]/[\\w:./+]+").matcher(vulnJson);
            return cm.find() ? cvssV3Score(cm.group()) : 7.0;
        }

        private static double cvssV3Score(String vector) {
            try {
                var parts = vector.split("/");
                var m = new HashMap<String, String>();
                for (int i = 1; i < parts.length; i++) {
                    var kv = parts[i].split(":", 2);
                    if (kv.length == 2) m.put(kv[0], kv[1]);
                }
                boolean sc = "C".equals(m.getOrDefault("S", "U"));
                double av = switch (m.getOrDefault("AV", "N")) {
                    case "N" -> 0.85; case "A" -> 0.62; case "L" -> 0.55; default -> 0.20;
                };
                double ac = "H".equals(m.getOrDefault("AC", "L")) ? 0.44 : 0.77;
                double pr = switch (m.getOrDefault("PR", "N")) {
                    case "N" -> 0.85;
                    case "L" -> sc ? 0.68 : 0.62;
                    default  -> sc ? 0.50 : 0.27;
                };
                double ui = "R".equals(m.getOrDefault("UI", "N")) ? 0.62 : 0.85;
                double ic = imp(m.getOrDefault("C", "H"));
                double ii = imp(m.getOrDefault("I", "H"));
                double ia = imp(m.getOrDefault("A", "H"));
                double iscBase = 1 - (1 - ic) * (1 - ii) * (1 - ia);
                double isc = sc
                        ? 7.52 * (iscBase - 0.029) - 3.25 * Math.pow(iscBase - 0.02, 15)
                        : 6.42 * iscBase;
                if (isc <= 0) return 0.0;
                double exp = 8.22 * av * ac * pr * ui;
                double raw = sc ? Math.min(1.08 * (isc + exp), 10.0)
                               : Math.min(isc + exp, 10.0);
                return Math.ceil(raw * 10) / 10.0;
            } catch (Exception e) {
                return 7.0;
            }
        }

        private static double imp(String v) {
            return switch (v) { case "N" -> 0.00; case "L" -> 0.22; default -> 0.56; };
        }

        // ── Output helpers ────────────────────────────────────────────────────
        private static void printText(List<Finding> findings, String project,
                                      Severity threshold, Output out) {
            if (findings.isEmpty()) {
                out.success("No vulnerabilities found in " + project);
                return;
            }
            long critical = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
            long high     = findings.stream().filter(f -> f.severity() == Severity.HIGH).count();
            long medium   = findings.stream().filter(f -> f.severity() == Severity.MEDIUM).count();
            long low      = findings.stream().filter(f -> f.severity() == Severity.LOW).count();

            for (var f : findings) {
                String line = String.format("  %-8s  %-20s (%4.1f)  %s:%s",
                        f.severity(), f.id(), f.score(), f.packageCoord(), f.version());
                if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH) {
                    out.fail(line);
                } else {
                    out.warn(line);
                }
                if (!f.summary().isEmpty()) {
                    out.info("             " + f.summary());
                }
            }
            out.info("");
            out.info(String.format("Summary: %d critical, %d high, %d medium, %d low",
                    critical, high, medium, low));
            out.info("Fail threshold: " + threshold);
        }

        private static void printJson(List<Finding> findings, String project, Output out) {
            long critical = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
            long high     = findings.stream().filter(f -> f.severity() == Severity.HIGH).count();
            long medium   = findings.stream().filter(f -> f.severity() == Severity.MEDIUM).count();
            long low      = findings.stream().filter(f -> f.severity() == Severity.LOW).count();

            var sb = new StringBuilder("{\n");
            sb.append("  \"project\": \"").append(ej(project)).append("\",\n");
            sb.append("  \"summary\": {");
            sb.append("\"critical\":").append(critical).append(',');
            sb.append("\"high\":").append(high).append(',');
            sb.append("\"medium\":").append(medium).append(',');
            sb.append("\"low\":").append(low).append("},\n");
            sb.append("  \"vulnerabilities\": [");
            for (int i = 0; i < findings.size(); i++) {
                if (i > 0) sb.append(',');
                var f = findings.get(i);
                sb.append("\n    {");
                sb.append("\"id\":\"").append(ej(f.id())).append("\",");
                sb.append("\"package\":\"").append(ej(f.packageCoord())).append("\",");
                sb.append("\"version\":\"").append(ej(f.version())).append("\",");
                sb.append("\"severity\":\"").append(f.severity()).append("\",");
                sb.append("\"score\":").append(f.score()).append(',');
                sb.append("\"summary\":\"").append(ej(f.summary())).append("\"");
                sb.append("}");
            }
            if (!findings.isEmpty()) sb.append("\n  ");
            sb.append("]\n}");
            out.info(sb.toString());
        }

        // ── JSON parsing helpers ──────────────────────────────────────────────

        // Extracts top-level JSON objects from an array starting at position arrayStart.
        // arrayStart must point at '['.
        private static List<String> extractObjectsFromPos(String json, int arrayStart) {
            if (arrayStart < 0 || arrayStart >= json.length()
                    || json.charAt(arrayStart) != '[') return List.of();
            var result = new ArrayList<String>();
            int depth = 0, objStart = -1;
            boolean inStr = false, esc = false;
            for (int i = arrayStart + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (esc)    { esc = false; continue; }
                if (inStr)  { if (c == '\\') esc = true; else if (c == '"') inStr = false; continue; }
                if (c == '"') { inStr = true; continue; }
                if (c == '{') { if (depth++ == 0) objStart = i; }
                else if (c == '}') {
                    if (--depth == 0 && objStart >= 0) {
                        result.add(json.substring(objStart, i + 1));
                        objStart = -1;
                    }
                } else if (c == ']' && depth == 0) break;
            }
            return result;
        }

        private static List<String> extractObjectsFromArray(String arrayStr) {
            int start = arrayStr.indexOf('[');
            return start < 0 ? List.of() : extractObjectsFromPos(arrayStr, start);
        }

        // An empty array ("[]") is valid hydrated data (no vulns for that coord).
        // A non-empty array is hydrated only if the vuln objects contain severity/aliases data.
        private static boolean isHydratedCache(String content) {
            if ("[]".equals(content)) return true;
            return content.contains("\"severity\"") || content.contains("\"aliases\"");
        }

        private static boolean isValidJsonArray(String s) {
            if (s == null || s.isEmpty()) return false;
            if (!s.startsWith("[") || !s.endsWith("]")) return false;
            int depth = 0;
            boolean inStr = false, esc = false;
            for (char c : s.toCharArray()) {
                if (esc) { esc = false; continue; }
                if (inStr) { if (c == '\\') esc = true; else if (c == '"') inStr = false; continue; }
                if (c == '"') { inStr = true; continue; }
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') { if (--depth < 0) return false; }
            }
            return depth == 0;
        }

        private static String extractString(String json, String key) {
            var m = Pattern.compile(
                    "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(json);
            return m.find() ? m.group(1) : null;
        }

        private static List<String> extractStringArray(String json, String key) {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) return List.of();
            int start = json.indexOf('[', ki);
            if (start < 0) return List.of();
            int end = json.indexOf(']', start);
            if (end < 0) return List.of();
            var inner = json.substring(start + 1, end);
            var result = new ArrayList<String>();
            var m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(inner);
            while (m.find()) result.add(m.group(1));
            return result;
        }

        // ── Utilities ─────────────────────────────────────────────────────────
        private static String sha256hex(String input) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(input.getBytes(StandardCharsets.UTF_8));
                var sb = new StringBuilder(64);
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        private static String ej(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
