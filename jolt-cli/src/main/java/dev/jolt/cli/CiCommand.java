package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.templates.ScaffoldTemplates;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "ci",
         description = "Generate CI pipeline configuration (GitHub Actions, GitLab CI).",
         subcommands = {
             CiCommand.GithubCommand.class,
             CiCommand.GitlabCommand.class
         })
final class CiCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Override
    public Integer call() {
        new Output(parent.quiet, parent.verbose, parent.json, parent.noColor)
            .info("Usage: jolt ci <subcommand>  (github | gitlab)");
        return 0;
    }

    @Command(name = "github",
             description = "Generate .github/workflows/build.yml (checkout, JDK, cache, build, test).")
    static final class GithubCommand implements Callable<Integer> {

        @ParentCommand CiCommand ci;

        @Option(names = "--force", description = "Overwrite existing workflow file.")
        boolean force;

        @Override
        public Integer call() {
            var out = new Output(ci.parent.quiet, ci.parent.verbose,
                                 ci.parent.json, ci.parent.noColor);
            Path cwd = ci.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) {
                out.fail("No project found.");
                return 3;
            }
            var project = projectOpt.get();
            Path root = project.root();
            Path workflowFile = root.resolve(".github/workflows/build.yml");

            if (Files.exists(workflowFile) && !force) {
                out.warn(".github/workflows/build.yml already exists. Use --force to overwrite.");
                return 0;
            }

            try {
                Files.createDirectories(workflowFile.getParent());
                String content = ScaffoldTemplates.GITHUB_WORKFLOW
                    .replace("{{javaVersion}}", "21");
                Files.writeString(workflowFile, content);
                out.success("Generated .github/workflows/build.yml");
                out.info("  Runs on: ubuntu-latest");
                out.info("  JDK    : temurin 21");
                out.info("  Steps  : checkout → setup-java → mvn verify → mvn package");
            } catch (IOException e) {
                out.fail("ci github failed: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "gitlab", description = "Generate .gitlab-ci.yml.")
    static final class GitlabCommand implements Callable<Integer> {

        @ParentCommand CiCommand ci;

        @Option(names = "--force", description = "Overwrite existing .gitlab-ci.yml.")
        boolean force;

        @Override
        public Integer call() {
            var out = new Output(ci.parent.quiet, ci.parent.verbose,
                                 ci.parent.json, ci.parent.noColor);
            Path cwd = ci.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) {
                out.fail("No project found.");
                return 3;
            }
            var project = projectOpt.get();
            Path root = project.root();
            Path ciFile = root.resolve(".gitlab-ci.yml");

            if (Files.exists(ciFile) && !force) {
                out.warn(".gitlab-ci.yml already exists. Use --force to overwrite.");
                return 0;
            }

            try {
                Files.writeString(ciFile, ScaffoldTemplates.GITLAB_CI);
                out.success("Generated .gitlab-ci.yml");
            } catch (IOException e) {
                out.fail("ci gitlab failed: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }
}
