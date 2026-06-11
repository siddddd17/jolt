package dev.jolt.cli;

import dev.jolt.adapter.maven.GraphResolver;
import dev.jolt.core.lock.LockfileWriter;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import dev.jolt.templates.ScaffoldTemplates;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "new",
         description = "Scaffold a new Java project from a template (maven, library, spring).")
final class NewCommand implements Callable<Integer> {

    private static final String SPRING_BOOT_FALLBACK = "3.4.5";
    private static final String DEFAULT_JAVA_VERSION = "21";

    @ParentCommand
    private Jolt parent;

    @Parameters(index = "0", paramLabel = "<template>", description = "Template: maven, library, spring.")
    private String template;

    @Parameters(index = "1", paramLabel = "<name>", description = "Project artifact ID (e.g. my-app).")
    private String name;

    @Option(names = "--group", paramLabel = "<groupId>", description = "Maven groupId (default: com.example).")
    private String groupId;

    @Option(names = "--java", paramLabel = "<version>", description = "Java version (default: 21).")
    private String javaVersion;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path base = parent.effectiveCwd().resolve(name);

        if (Files.exists(base)) {
            out.fail("Directory already exists: " + base);
            return 1;
        }

        String gid  = groupId   != null ? groupId   : "com.example";
        String jv   = javaVersion != null ? javaVersion : DEFAULT_JAVA_VERSION;
        String pkg  = toPackage(gid, name);
        String cls  = toClassName(name);

        try {
            return switch (template.toLowerCase(Locale.ROOT)) {
                case "spring"  -> scaffoldSpring(out, base, gid, name, pkg, cls, jv);
                case "maven"   -> scaffoldMaven(out, base, gid, name, pkg, cls, jv);
                case "library" -> scaffoldLibrary(out, base, gid, name, pkg, cls, jv);
                default -> {
                    out.fail("Unknown template '" + template + "'. Available: maven, library, spring");
                    yield 2;
                }
            };
        } catch (Exception e) {
            out.fail("Scaffolding failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    private int scaffoldSpring(Output out, Path base, String gid, String aid, String pkg, String cls, String jv)
            throws IOException, InterruptedException {
        String sbVersion = resolveSpringBootVersion(out);
        out.verbose("Using Spring Boot " + sbVersion);

        Files.createDirectories(base);

        // pom.xml
        write(base.resolve("pom.xml"),
            ScaffoldTemplates.SPRING_POM
                .replace("{{groupId}}", gid)
                .replace("{{artifactId}}", aid)
                .replace("{{springBootVersion}}", sbVersion)
                .replace("{{javaVersion}}", jv)
                .replace("{{package}}", pkg)
                .replace("{{className}}", cls + "Application"));

        // Source directories
        Path mainJava = base.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Path mainRes  = base.resolve("src/main/resources");
        Path testJava = base.resolve("src/test/java").resolve(pkg.replace('.', '/'));
        Path ctrlDir  = mainJava.resolve("controller");
        // Create layered package dirs (service, repository, entity, dto, config, exception)
        for (String layer : new String[]{"controller", "service", "repository", "entity", "dto", "config", "exception"}) {
            Files.createDirectories(mainJava.resolve(layer));
        }
        Files.createDirectories(mainRes);
        Files.createDirectories(testJava);

        // Main application class
        write(mainJava.resolve(cls + "Application.java"),
            ScaffoldTemplates.SPRING_APP
                .replace("{{package}}", pkg)
                .replace("{{className}}", cls + "Application"));

        // Controller
        String controllerName = cls + "Controller";
        write(ctrlDir.resolve(controllerName + ".java"),
            ScaffoldTemplates.SPRING_CONTROLLER
                .replace("{{package}}", pkg)
                .replace("{{controllerName}}", controllerName));

        // application.properties
        write(mainRes.resolve("application.properties"),
            "spring.application.name=" + aid + "\n");

        // Smoke test
        write(testJava.resolve(cls + "ApplicationTests.java"),
            ScaffoldTemplates.SPRING_APP_TEST
                .replace("{{package}}", pkg)
                .replace("{{className}}", cls + "Application"));

        // .gitignore
        write(base.resolve(".gitignore"), ScaffoldTemplates.SPRING_GITIGNORE);

        // Git init
        gitInit(out, base);

        writeLock(out, base, aid);

        out.success("Created " + aid + " (Spring Boot " + sbVersion + ", Java " + jv + ")");
        out.info("  package : " + pkg);
        out.info("  class   : " + cls + "Application");
        out.info("");
        out.info("Next steps:");
        out.info("  cd " + aid);
        out.info("  jolt run");
        return 0;
    }

    private int scaffoldMaven(Output out, Path base, String gid, String aid, String pkg, String cls, String jv)
            throws IOException, InterruptedException {
        Files.createDirectories(base);

        write(base.resolve("pom.xml"),
            ScaffoldTemplates.MAVEN_POM
                .replace("{{groupId}}", gid)
                .replace("{{artifactId}}", aid)
                .replace("{{javaVersion}}", jv));

        Path mainJava = base.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Path testJava = base.resolve("src/test/java").resolve(pkg.replace('.', '/'));
        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);

        write(mainJava.resolve("Main.java"),
            ScaffoldTemplates.MAVEN_MAIN
                .replace("{{package}}", pkg)
                .replace("{{artifactId}}", aid));

        write(testJava.resolve("MainTest.java"),
            ScaffoldTemplates.MAVEN_TEST.replace("{{package}}", pkg));

        write(base.resolve(".gitignore"), ScaffoldTemplates.MAVEN_GITIGNORE);
        gitInit(out, base);

        writeLock(out, base, aid);

        out.success("Created " + aid + " (Maven, Java " + jv + ")");
        return 0;
    }

    private int scaffoldLibrary(Output out, Path base, String gid, String aid, String pkg, String cls, String jv)
            throws IOException, InterruptedException {
        Files.createDirectories(base);

        write(base.resolve("pom.xml"),
            ScaffoldTemplates.LIBRARY_POM
                .replace("{{groupId}}", gid)
                .replace("{{artifactId}}", aid)
                .replace("{{javaVersion}}", jv));

        Path mainJava = base.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Path testJava = base.resolve("src/test/java").resolve(pkg.replace('.', '/'));
        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);

        write(testJava.resolve(cls + "Test.java"),
            ScaffoldTemplates.MAVEN_TEST.replace("{{package}}", pkg));

        write(base.resolve(".gitignore"), ScaffoldTemplates.MAVEN_GITIGNORE);
        gitInit(out, base);

        writeLock(out, base, aid);

        out.success("Created " + aid + " (library, Java " + jv + ")");
        return 0;
    }

    private void writeLock(Output out, Path projectRoot, String artifactId) {
        out.verbose("Resolving dependency graph and writing jolt.lock…");
        try {
            Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            var resolver = new GraphResolver();
            var session  = resolver.newSession(localRepo);
            var result   = resolver.resolve(projectRoot, session, java.util.List.of(GraphResolver.CENTRAL));
            var lockfile = LockfileWriter.fromGraph(result.graph(), artifactId);
            LockfileWriter.write(lockfile, projectRoot.resolve("jolt.lock"));
            out.verbose("jolt.lock written (" + result.graph().size() + " artifacts)");
        } catch (Exception e) {
            out.warn("Could not write jolt.lock: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
        }
    }

    private void gitInit(Output out, Path dir) throws IOException, InterruptedException {
        var gitOpt = ToolFinder.find("git");
        if (gitOpt.isEmpty()) {
            out.verbose("git not found — skipping git init");
            return;
        }
        String git = gitOpt.get().toString();
        ProcessRunner.capture(dir, git, "init");
        ProcessRunner.capture(dir, git, "add", "-A");
        var commit = ProcessRunner.capture(dir, git, "commit", "-m", "Initial scaffold by jolt");
        if (commit.succeeded()) {
            out.verbose("git: initial commit created");
        } else {
            out.verbose("git commit skipped (user config may not be set): " + commit.output());
        }
    }

    private String resolveSpringBootVersion(Output out) {
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://search.maven.org/solrsearch/select" +
                    "?q=g:org.springframework.boot+AND+a:spring-boot-starter-parent&rows=1&wt=json"))
                .timeout(Duration.ofSeconds(8))
                .GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            int idx = body.indexOf("\"latestVersion\":\"");
            if (idx >= 0) {
                int start = idx + 17;
                int end = body.indexOf('"', start);
                if (end > start) return body.substring(start, end);
            }
        } catch (Exception e) {
            out.verbose("Could not resolve Spring Boot version from Maven Central: " + e.getMessage());
        }
        out.verbose("Falling back to Spring Boot " + SPRING_BOOT_FALLBACK);
        return SPRING_BOOT_FALLBACK;
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /** com.example + my-service → com.example.myservice */
    static String toPackage(String groupId, String artifactId) {
        String suffix = artifactId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return groupId + "." + suffix;
    }

    /** my-service → MyService */
    static String toClassName(String artifactId) {
        return Arrays.stream(artifactId.split("[^a-zA-Z0-9]"))
            .filter(s -> !s.isEmpty())
            .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT))
            .collect(Collectors.joining());
    }
}
