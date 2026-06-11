package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.project.ProjectInfo;
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

@Command(name = "run",
         description = "Build and run the project's main class or entry point.")
final class RunCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Parameters(index = "0", paramLabel = "<target>", description = "Main class (simple name or FQN).", arity = "0..1")
    private String target;

    @Option(names = "--jvm-args", paramLabel = "<args>", description = "Additional JVM arguments.")
    private String jvmArgs;

    @Option(names = "--profile", paramLabel = "<profile>", description = "Spring profile to activate.")
    private String profile;

    @Option(names = "--module", paramLabel = "<module>", description = "Module selector in a multi-module reactor.")
    private String module;

    @Parameters(index = "1..*", paramLabel = "<args>", description = "Arguments passed to the program.")
    private List<String> programArgs;

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
            return switch (project.buildSystem()) {
                case MAVEN -> runMaven(out, root, project, mvn);
                case GRADLE -> {
                    var adapter = new dev.jolt.adapter.gradle.GradleAdapter();
                    var req = new dev.jolt.core.adapter.RunRequest(
                            target,
                            programArgs != null ? programArgs : List.of(),
                            jvmArgs != null ? List.of(jvmArgs) : List.of(),
                            profile != null ? List.of(profile) : List.of(),
                            false);
                    yield adapter.run(root, req);
                }
                case UNKNOWN -> {
                    out.fail("Could not detect build system in " + root);
                    yield 3;
                }
            };
        } catch (Exception e) {
            out.fail("run failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    private int runMaven(Output out, Path root, ProjectInfo project, String mvn)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(mvn);

        if (project.isSpringBoot()) {
            cmd.add("spring-boot:run");
            if (profile != null) {
                cmd.add("-Dspring-boot.run.profiles=" + profile);
            }
            if (jvmArgs != null) {
                cmd.add("-Dspring-boot.run.jvmArguments=" + jvmArgs);
            }
            if (programArgs != null && !programArgs.isEmpty()) {
                cmd.add("-Dspring-boot.run.arguments=" + String.join(",", programArgs));
            }
        } else {
            // Plain Maven project: detect main class or use target parameter
            String mainClass = target;
            if (mainClass == null) {
                out.fail("No main class specified. Use 'jolt run <ClassName>' or run from a Spring Boot project.");
                return 1;
            }
            cmd.addAll(List.of("exec:java", "-Dexec.mainClass=" + mainClass));
            if (programArgs != null && !programArgs.isEmpty()) {
                cmd.add("-Dexec.args=" + String.join(" ", programArgs));
            }
        }

        cmd.add("--batch-mode");
        out.verbose("exec: " + String.join(" ", cmd));
        return ProcessRunner.run(root, cmd);
    }
}
