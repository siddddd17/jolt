package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "migrate",
         description = "Migrate Java version or Spring Boot version using OpenRewrite.",
         subcommands = {
             MigrateCommand.JavaCommand.class,
             MigrateCommand.SpringCommand.class
         })
final class MigrateCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Override
    public Integer call() {
        new Output(parent.quiet, parent.verbose, parent.json, parent.noColor)
            .info("Usage: jolt migrate <java|spring> <version>");
        return 0;
    }

    // ── migrate java ──────────────────────────────────────────────────────────

    @Command(name = "java",
             description = "Upgrade Java version via OpenRewrite (e.g. jolt migrate java 21).")
    static final class JavaCommand implements Callable<Integer> {

        @ParentCommand MigrateCommand migrate;

        @Parameters(index = "0", paramLabel = "<version>",
                    description = "Target Java version: 11, 17, 21.")
        private String version;

        @Override
        public Integer call() {
            var out = new Output(migrate.parent.quiet, migrate.parent.verbose,
                                 migrate.parent.json, migrate.parent.noColor);
            Path cwd = migrate.parent.effectiveCwd();
            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }

            String recipe = "org.openrewrite.java.migrate.UpgradeToJava" + version;
            String coords = "org.openrewrite.recipe:rewrite-migrate-java:LATEST";
            return runRewrite(out, projectOpt.get().root(), recipe, coords,
                              "Java " + version, migrate.parent.verbose);
        }
    }

    // ── migrate spring ────────────────────────────────────────────────────────

    @Command(name = "spring",
             description = "Upgrade Spring Boot version via OpenRewrite (e.g. jolt migrate spring 3.3).")
    static final class SpringCommand implements Callable<Integer> {

        @ParentCommand MigrateCommand migrate;

        @Parameters(index = "0", paramLabel = "<version>",
                    description = "Target Spring Boot version (e.g. 3.2, 3.3, 3.4).")
        private String version;

        @Override
        public Integer call() {
            var out = new Output(migrate.parent.quiet, migrate.parent.verbose,
                                 migrate.parent.json, migrate.parent.noColor);
            Path cwd = migrate.parent.effectiveCwd();
            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }

            String vNorm   = version.replace(".", "_");
            int    major   = Integer.parseInt(version.split("\\.")[0]);
            String recipe  = "org.openrewrite.java.spring.boot" + major
                             + ".UpgradeSpringBoot_" + vNorm;
            String coords  = "org.openrewrite.recipe:rewrite-spring:LATEST";
            return runRewrite(out, projectOpt.get().root(), recipe, coords,
                              "Spring Boot " + version, migrate.parent.verbose);
        }
    }

    // ── Shared OpenRewrite runner ─────────────────────────────────────────────

    private static int runRewrite(Output out, Path root, String recipe,
                                   String recipeCoords, String label, boolean verbose) {
        String mvn = ToolFinder.find("mvn").map(Path::toString).orElse("mvn");

        out.info("Running OpenRewrite: " + recipe);

        List<String> cmd = new ArrayList<>(List.of(
                mvn,
                "org.openrewrite.maven:rewrite-maven-plugin:run",
                "-Drewrite.activeRecipes=" + recipe,
                "-Drewrite.recipeArtifactCoordinates=" + recipeCoords,
                "--batch-mode"));
        if (!verbose) cmd.add("-q");

        out.verbose("exec: " + String.join(" ", cmd));

        int code;
        try {
            // Stream Maven output so user sees progress
            code = ProcessRunner.run(root, cmd);
        } catch (Exception e) {
            out.fail("migrate failed: " + e.getMessage());
            if (verbose) e.printStackTrace();
            return 1;
        }

        if (code != 0) {
            out.fail("OpenRewrite recipe failed (exit " + code + ").");
            out.info("  Make sure the recipe artifact is available on Maven Central.");
            out.info("  Recipe: " + recipe);
            return 1;
        }

        out.success("Migration to " + label + " complete.");
        printDiffSummary(out, root);
        return 0;
    }

    private static void printDiffSummary(Output out, Path root) {
        try {
            // Files modified
            var names = ProcessRunner.capture(root, "git", "diff", "--name-only");
            if (names.succeeded() && !names.output().isBlank()) {
                out.info("");
                out.info("Files modified:");
                for (String file : names.output().split("\n")) {
                    if (!file.isBlank()) {
                        // Per-file stat: additions/deletions
                        var stat = ProcessRunner.capture(root, "git", "diff",
                                "--numstat", "--", file.trim());
                        String adddel = "";
                        if (stat.succeeded() && !stat.output().isBlank()) {
                            String[] parts = stat.output().trim().split("\\s+");
                            if (parts.length >= 2) {
                                adddel = "  +" + parts[0] + " -" + parts[1];
                            }
                        }
                        out.info("  " + file.trim() + adddel);
                    }
                }
                // Overall summary
                var summary = ProcessRunner.capture(root, "git", "diff", "--shortstat");
                if (summary.succeeded() && !summary.output().isBlank()) {
                    out.info(summary.output().trim());
                }
            } else {
                out.info("No tracked files changed (recipe may have made no modifications).");
            }
        } catch (Exception e) {
            out.verbose("Could not compute diff summary: " + e.getMessage());
        }
    }
}
