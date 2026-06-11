package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;

@Command(name = "clean",
         description = "Clean build outputs. --all also clears Jolt's local caches.")
final class CleanCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Option(names = "--all", description = "Also clear Jolt's local caches.")
    private boolean all;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path cwd = parent.effectiveCwd();

        var projectOpt = ProjectDetector.detect(cwd);
        if (projectOpt.isEmpty()) {
            out.fail("No project found. Run from a directory containing pom.xml or build.gradle.");
            return 3;
        }
        var project = projectOpt.get();
        Path root = project.root();
        String mvn = ToolFinder.find("mvn").map(Path::toString).orElse("mvn");

        try {
            int code = switch (project.buildSystem()) {
                case MAVEN -> ProcessRunner.run(root, mvn, "clean", "--batch-mode");
                case GRADLE -> {
                    var adapter = new dev.jolt.adapter.gradle.GradleAdapter();
                    yield adapter.clean(root);
                }
                case UNKNOWN -> {
                    out.fail("Could not detect build system in " + root);
                    yield 3;
                }
            };
            if (all && code == 0) {
                cleanJoltCaches(out);
            }
            return code;
        } catch (Exception e) {
            out.fail("clean failed: " + e.getMessage());
            return 1;
        }
    }

    private void cleanJoltCaches(Output out) {
        Path cacheDir = Path.of(System.getProperty("user.home"), ".jolt", "cache");
        if (!Files.exists(cacheDir)) {
            out.verbose("Jolt cache directory does not exist — nothing to clear.");
            return;
        }
        try (var stream = Files.walk(cacheDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
            out.success("Jolt cache cleared: " + cacheDir);
        } catch (IOException e) {
            out.warn("Could not clear Jolt cache: " + e.getMessage());
        }
    }
}
