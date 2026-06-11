package dev.jolt.cli;

import dev.jolt.adapter.maven.ReactorDetector;
import dev.jolt.core.project.AffectedComputer;
import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.project.ReactorModule;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "test",
         description = "Run tests. Supports class, method, and --affected filtering.")
final class TestCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Parameters(index = "0", paramLabel = "<target>",
                description = "Test target: ClassName or ClassName#method.", arity = "0..1")
    private String target;

    @Option(names = "--affected", description = "Run only tests in modules changed since <base>.")
    private boolean affected;

    @Option(names = "--since", paramLabel = "<ref>",
            description = "Git ref for --affected comparison (overrides GITHUB_BASE_REF / HEAD~1).")
    private String since;

    @Option(names = "--module", paramLabel = "<module>", description = "Restrict to a module.")
    private String module;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path cwd = parent.effectiveCwd();

        if (affected) {
            return runAffected(out, cwd);
        }

        var projectOpt = ProjectDetector.detect(cwd);
        if (projectOpt.isEmpty()) {
            out.fail("No project found. Run from a directory containing pom.xml or build.gradle.");
            return 3;
        }
        var project = projectOpt.get();
        Path root = project.root();
        String mvn = ToolFinder.find("mvn").map(Path::toString).orElse("mvn");

        try {
            return switch (project.buildSystem()) {
                case MAVEN -> runMavenTest(out, root, mvn, null);
                case GRADLE -> {
                    var adapter = new dev.jolt.adapter.gradle.GradleAdapter();
                    var req = target != null
                            ? dev.jolt.core.adapter.TestRequest.forClass(target)
                            : dev.jolt.core.adapter.TestRequest.all();
                    yield adapter.test(root, req);
                }
                case UNKNOWN -> {
                    out.fail("Could not detect build system in " + root);
                    yield 3;
                }
            };
        } catch (Exception e) {
            out.fail("test failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    private int runAffected(Output out, Path cwd) {
        // 1. Find reactor root by walking up from cwd
        var rootOpt = ReactorDetector.findRoot(cwd);
        if (rootOpt.isEmpty()) {
            out.fail("No project found. Run from a directory containing pom.xml.");
            return 3;
        }
        Path reactorRoot = rootOpt.get();

        // 2. Scan the module graph (lightweight — no Aether)
        var reactor = ReactorDetector.scan(reactorRoot);

        // 3. Determine the base ref and compute affected modules
        String baseRef = AffectedComputer.resolveBaseRef(since);
        List<ReactorModule> affectedModules = AffectedComputer.compute(reactor, baseRef);

        if (affectedModules.isEmpty()) {
            out.info("No modules affected since " + baseRef + ". Nothing to test.");
            return 0;
        }

        out.info("Affected since " + baseRef + ":");
        for (var m : affectedModules) {
            out.info("  " + m.coordinates().artifactId() + "  (" + m.path() + ")");
        }

        // 4. Build the Maven command
        String mvn = ToolFinder.find("mvn").map(Path::toString).orElse("mvn");
        String plArg = affectedModules.stream()
                .map(m -> m.path().toString())
                .collect(Collectors.joining(","));

        List<String> cmd = new ArrayList<>();
        cmd.add(mvn);
        cmd.add("test");
        cmd.add("--batch-mode");
        cmd.add("-pl");
        cmd.add(plArg);
        cmd.add("-am");   // also make upstream modules (compile, don't rerun tests)

        if (target != null) {
            cmd.add("-Dtest=" + target);
            cmd.add("-DfailIfNoTests=false");
        }

        out.verbose("exec (from " + reactorRoot + "): " + String.join(" ", cmd));
        try {
            int code = ProcessRunner.run(reactorRoot, cmd);
            if (code != 0) out.fail("Tests failed (exit " + code + ")");
            return code == 0 ? 0 : 1;
        } catch (Exception e) {
            out.fail("test --affected failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    private int runMavenTest(Output out, Path root, String mvn, String plOverride) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(mvn);
        cmd.add("test");
        cmd.add("--batch-mode");

        if (target != null) {
            cmd.add("-Dtest=" + target);
            cmd.add("-DfailIfNoTests=false");
        }
        if (plOverride != null) {
            cmd.add("-pl");
            cmd.add(plOverride);
            cmd.add("-am");
        } else if (module != null) {
            cmd.add("-pl");
            cmd.add(module);
            cmd.add("-am");
        }
        if (parent.locked) {
            out.verbose("--locked: lockfile verification will be enforced in Phase 2");
        }

        out.verbose("exec: " + String.join(" ", cmd));
        int code = ProcessRunner.run(root, cmd);
        if (code != 0) {
            out.fail("Tests failed (exit " + code + ")");
        }
        return code == 0 ? 0 : 1;
    }
}
