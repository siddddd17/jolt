package dev.jolt.adapter.gradle;

import dev.jolt.core.adapter.BuildSystemAdapter;
import dev.jolt.core.adapter.RunRequest;
import dev.jolt.core.adapter.TestRequest;
import dev.jolt.core.graph.Conflict;
import dev.jolt.core.graph.DependencyGraph;
import dev.jolt.core.graph.DependencyNode;
import dev.jolt.core.graph.GA;
import dev.jolt.core.graph.GAV;
import dev.jolt.core.graph.Scope;
import org.gradle.tooling.GradleConnector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GradleAdapter implements BuildSystemAdapter {

    // Config names we care about, in priority order for scope resolution
    private static final List<String> TARGET_CONFIGS =
            List.of("compileClasspath", "runtimeClasspath",
                    "testCompileClasspath", "testRuntimeClasspath");

    @Override
    public boolean detect(Path projectRoot) {
        return Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"));
    }

    @Override
    public DependencyGraph resolve(Path projectRoot) {
        Path[] paths = new Path[2]; // [0]=initScript, [1]=outputFile
        try {
            paths[1] = Files.createTempFile("jolt-deps-", ".json");
            paths[0] = Files.createTempFile("jolt-resolve-", ".gradle");
            Files.writeString(paths[0], buildInitScript(paths[1]), StandardCharsets.UTF_8);

            try (var connection = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile())
                    .connect()) {
                connection.newBuild()
                        .addArguments("--init-script", paths[0].toAbsolutePath().toString(),
                                "--quiet", "--no-daemon", "--warning-mode", "none")
                        .forTasks("help")
                        .setStandardOutput(OutputStream.nullOutputStream())
                        .setStandardError(OutputStream.nullOutputStream())
                        .run();
            }
            return parseGraph(paths[1]);
        } catch (Exception e) {
            throw new RuntimeException("Gradle dependency resolution failed: " + e.getMessage(), e);
        } finally {
            quietly(() -> { if (paths[0] != null) Files.deleteIfExists(paths[0]); });
            quietly(() -> { if (paths[1] != null) Files.deleteIfExists(paths[1]); });
        }
    }

    @Override
    public int test(Path projectRoot, TestRequest req) {
        List<String> cmd = new ArrayList<>();
        cmd.add(findGradlew(projectRoot));
        cmd.add("test");
        cmd.add("--no-daemon");
        if (req.testPattern() != null) {
            cmd.add("--tests");
            cmd.add(req.methodPattern() != null
                    ? req.testPattern() + "." + req.methodPattern()
                    : req.testPattern() + ".*");
        }
        try {
            return dev.jolt.core.util.ProcessRunner.run(projectRoot, cmd);
        } catch (Exception e) {
            throw new RuntimeException("gradle test failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int run(Path projectRoot, RunRequest req) {
        List<String> cmd = new ArrayList<>();
        cmd.add(findGradlew(projectRoot));
        boolean isSpring = isSpringBootProject(projectRoot);
        cmd.add(isSpring ? "bootRun" : "run");
        cmd.add("--no-daemon");
        if (!req.jvmArgs().isEmpty()) {
            cmd.add("--args=" + String.join(" ", req.jvmArgs()));
        }
        if (!req.profiles().isEmpty()) {
            cmd.add("-Dspring.profiles.active=" + String.join(",", req.profiles()));
        }
        try {
            return dev.jolt.core.util.ProcessRunner.run(projectRoot, cmd);
        } catch (Exception e) {
            throw new RuntimeException("gradle run failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int clean(Path projectRoot) {
        try {
            return dev.jolt.core.util.ProcessRunner.run(projectRoot,
                    findGradlew(projectRoot), "clean", "--no-daemon");
        } catch (Exception e) {
            throw new RuntimeException("gradle clean failed: " + e.getMessage(), e);
        }
    }

    // ── Dependency manipulation ───────────────────────────────────────────────

    public void addDependency(Path buildFile, String groupId, String artifactId,
                              String version, String joltScope) throws IOException {
        String gradleScope = toGradleScope(joltScope);
        String entry = "    " + gradleScope + " '" + groupId + ":" + artifactId
                + (version != null ? ":" + version : "") + "'";
        String content = Files.readString(buildFile, StandardCharsets.UTF_8);
        int depBlock = content.lastIndexOf("dependencies {");
        if (depBlock < 0) {
            // Append a new dependencies block
            content = content + "\ndependencies {\n" + entry + "\n}\n";
        } else {
            int closing = findMatchingBrace(content, depBlock + "dependencies {".length() - 1);
            if (closing < 0) closing = content.lastIndexOf('}');
            content = content.substring(0, closing) + entry + "\n" + content.substring(closing);
        }
        Files.writeString(buildFile, content, StandardCharsets.UTF_8);
    }

    public void removeDependency(Path buildFile, String groupId, String artifactId)
            throws IOException {
        String needle = groupId + ":" + artifactId;
        String content = Files.readString(buildFile, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (!line.contains(needle)) sb.append(line).append("\n");
        }
        // Remove trailing extra newline
        String result = sb.toString();
        if (result.endsWith("\n\n") && content.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        Files.writeString(buildFile, result, StandardCharsets.UTF_8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildInitScript(Path outputFile) {
        // Use the output file path with forward slashes for Groovy compatibility
        String outPath = outputFile.toAbsolutePath().toString().replace("\\", "/");
        // Note: %% in the format string becomes % in the output (for Groovy's encodeHex)
        return """
                gradle.projectsEvaluated { g ->
                    def outputFile = new File('%s')
                    def targetConfigs = ["compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath"]
                    def scopeMap = [compileClasspath: "compile", runtimeClasspath: "runtime", testCompileClasspath: "test", testRuntimeClasspath: "test"]
                    def scopeOrd = { s -> s == "compile" ? 0 : s == "runtime" ? 1 : 2 }
                    def computeSha256 = { File file ->
                        if (!file || !file.exists()) return ""
                        def d = java.security.MessageDigest.getInstance("SHA-256")
                        file.withInputStream { is ->
                            def buf = new byte[8192]; int n
                            while ((n = is.read(buf)) != -1) d.update(buf, 0, n)
                        }
                        d.digest().encodeHex().toString()
                    }
                    def seen = [:]; def results = []
                    g.rootProject.allprojects.each { project ->
                        targetConfigs.each { cfgName ->
                            def config = project.configurations.findByName(cfgName)
                            if (config == null || !config.canBeResolved) return
                            try {
                                config.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                                    def id = artifact.moduleVersion.id
                                    def key = id.group + ":" + id.name
                                    def scope = scopeMap[cfgName]
                                    if (!seen.containsKey(key)) {
                                        seen[key] = scope
                                        results << [group: id.group, name: id.name, version: id.version, scope: scope, sha256: computeSha256(artifact.file)]
                                    } else if (scopeOrd(scope) < scopeOrd(seen[key])) {
                                        def ex = results.find { it.group == id.group && it.name == id.name }
                                        if (ex) { ex.scope = scope; seen[key] = scope }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    outputFile.text = groovy.json.JsonOutput.toJson(results)
                }
                """.formatted(outPath);
    }

    private DependencyGraph parseGraph(Path outputFile) throws IOException {
        if (!Files.exists(outputFile)) return DependencyGraph.empty();
        String json = Files.readString(outputFile, StandardCharsets.UTF_8).trim();
        if (json.isEmpty() || json.equals("[]")) return DependencyGraph.empty();

        var allNodes = new LinkedHashMap<GA, DependencyNode>();
        var pat = Pattern.compile(
                "\\{\"group\":\"([^\"]+)\",\"name\":\"([^\"]+)\",\"version\":\"([^\"]+)\",\"scope\":\"([^\"]+)\",\"sha256\":\"([^\"]*)\"\\}");
        Matcher m = pat.matcher(json);
        while (m.find()) {
            String group   = m.group(1);
            String name    = m.group(2);
            String version = m.group(3);
            String scope   = m.group(4);
            String sha256  = m.group(5);
            var ga  = new GA(group, name);
            var gav = new GAV(group, name, version);
            var node = new DependencyNode(
                    gav,
                    List.of(version),
                    version,
                    Scope.of(scope),
                    null, "jar", false,
                    List.of(), "gradle",
                    sha256, "",
                    List.of());
            allNodes.put(ga, node);
        }
        // All compile-scope nodes are roots (no parents in the flat model)
        var roots = allNodes.values().stream()
                .filter(n -> n.scope() == Scope.COMPILE)
                .collect(java.util.stream.Collectors.toList());
        return new DependencyGraph(roots, allNodes, List.of());
    }

    private String findGradlew(Path projectRoot) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectRoot.resolve(isWindows ? "gradlew.bat" : "gradlew");
        if (Files.exists(wrapper)) return wrapper.toAbsolutePath().toString();
        return "gradle";
    }

    private boolean isSpringBootProject(Path projectRoot) {
        try {
            Path buildFile = projectRoot.resolve("build.gradle");
            if (Files.exists(buildFile)) {
                String content = Files.readString(buildFile, StandardCharsets.UTF_8);
                return content.contains("org.springframework.boot");
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static String toGradleScope(String joltScope) {
        return switch (joltScope == null ? "compile" : joltScope.toLowerCase()) {
            case "test"     -> "testImplementation";
            case "runtime"  -> "runtimeOnly";
            case "provided" -> "compileOnly";
            default         -> "implementation";
        };
    }

    private static int findMatchingBrace(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i; }
        }
        return -1;
    }

    private static void quietly(ThrowingRunnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }
}
