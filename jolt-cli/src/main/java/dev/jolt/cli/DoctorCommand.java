package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command(name = "doctor",
         description = "Validate the environment for Java development (JDK, build tools, Git, Docker, network).")
final class DoctorCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path cwd = parent.effectiveCwd();
        boolean anyHardFail = false;

        out.info("Checking environment...\n");

        // ── Java ────────────────────────────────────────────────────────────
        out.info("Java");
        String activeJavaMajor = null;
        var javaOpt = ToolFinder.find("java");
        if (javaOpt.isEmpty()) {
            out.fail("  java : not found on PATH");
            anyHardFail = true;
        } else {
            try {
                var result = ProcessRunner.capture(cwd, javaOpt.get().toString(), "-version");
                String versionLine = result.output().lines().findFirst().orElse("unknown");
                out.success("  java     : " + versionLine);
                // Extract major version (e.g. "21" from 'openjdk version "21.0.3"')
                var m = Pattern.compile("[\"](\\d+)").matcher(versionLine);
                if (m.find()) activeJavaMajor = m.group(1);
            } catch (Exception e) {
                out.fail("  java     : error running java -version: " + e.getMessage());
                anyHardFail = true;
            }
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            out.info("  JAVA_HOME: " + javaHome);
        } else {
            out.warn("  JAVA_HOME: not set (recommended for reproducibility)");
        }
        // ── .java-version pin ────────────────────────────────────────────────
        var projectOptForJdk = ProjectDetector.detect(cwd);
        Path javaVersionFile = projectOptForJdk.map(p -> p.root().resolve(".java-version"))
                .orElse(cwd.resolve(".java-version"));
        if (Files.exists(javaVersionFile)) {
            try {
                String pinned = Files.readString(javaVersionFile, StandardCharsets.UTF_8).strip();
                out.info("  .java-version: " + pinned + " (pinned via jolt jdk use)");
                if (activeJavaMajor != null && !pinned.equals(activeJavaMajor)
                        && !pinned.startsWith(activeJavaMajor + ".")) {
                    out.warn("  ⚠  Active JDK " + activeJavaMajor
                            + " does not match pinned " + pinned
                            + ". Run: jolt jdk install " + pinned
                            + " && jolt jdk use " + pinned);
                } else if (activeJavaMajor != null) {
                    out.success("  JDK version matches pin (" + pinned + ")");
                }
            } catch (Exception e) {
                out.warn("  Could not read .java-version: " + e.getMessage());
            }
        }

        // ── Maven ────────────────────────────────────────────────────────────
        out.info("\nMaven");
        var mvnOpt = ToolFinder.find("mvn");
        if (mvnOpt.isEmpty()) {
            out.warn("  mvn: not found on PATH (required for Maven projects)");
        } else {
            try {
                var result = ProcessRunner.capture(cwd, mvnOpt.get().toString(), "-v");
                String firstLine = result.output().lines().findFirst().orElse("unknown");
                out.success("  " + firstLine);
                out.info("  path     : " + mvnOpt.get());
            } catch (Exception e) {
                out.warn("  mvn: error running mvn -v: " + e.getMessage());
            }
        }

        // ── Git ──────────────────────────────────────────────────────────────
        out.info("\nGit");
        var gitOpt = ToolFinder.find("git");
        if (gitOpt.isEmpty()) {
            out.warn("  git: not found on PATH");
        } else {
            try {
                var ver = ProcessRunner.capture(cwd, gitOpt.get().toString(), "--version");
                out.success("  " + ver.firstLine());

                var name  = ProcessRunner.capture(cwd, gitOpt.get().toString(), "config", "--global", "user.name");
                var email = ProcessRunner.capture(cwd, gitOpt.get().toString(), "config", "--global", "user.email");
                if (name.succeeded() && !name.output().isBlank()
                    && email.succeeded() && !email.output().isBlank()) {
                    out.success("  user     : " + name.output().trim() + " <" + email.output().trim() + ">");
                } else {
                    out.warn("  user     : git user.name / user.email not configured");
                }
            } catch (Exception e) {
                out.warn("  git: " + e.getMessage());
            }
        }

        // ── Docker ───────────────────────────────────────────────────────────
        out.info("\nDocker");
        var dockerOpt = ToolFinder.find("docker");
        if (dockerOpt.isEmpty()) {
            out.warn("  docker: not found on PATH (required for jolt docker commands)");
        } else {
            try {
                var ver = ProcessRunner.capture(cwd, dockerOpt.get().toString(), "--version");
                out.success("  " + ver.firstLine());

                var info = ProcessRunner.capture(cwd, dockerOpt.get().toString(), "info", "--format", "{{.ServerVersion}}");
                String infoOut = info.output().trim();
                if (info.succeeded() && !infoOut.isBlank() && !infoOut.contains("Cannot connect")) {
                    out.success("  daemon   : reachable (server " + infoOut + ")");
                } else {
                    out.warn("  daemon   : not reachable (start Docker Desktop or dockerd)");
                }
            } catch (Exception e) {
                out.warn("  docker: " + e.getMessage());
            }
        }

        // ── Project (if in a project dir) ────────────────────────────────────
        var projectOpt = ProjectDetector.detect(cwd);
        if (projectOpt.isPresent()) {
            var p = projectOpt.get();
            out.info("\nProject: " + p.artifactId() + " (" + p.buildSystem().displayName()
                     + (p.isSpringBoot() ? ", Spring Boot" : "") + ")");
        }

        out.info("");
        if (anyHardFail) {
            out.fail("One or more required tools are missing. Run the suggested remediation steps above.");
            return 3;
        }
        out.success("Environment looks good.");
        return 0;
    }
}
