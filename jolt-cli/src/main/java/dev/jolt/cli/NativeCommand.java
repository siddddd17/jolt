package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.util.ProcessRunner;
import dev.jolt.core.util.ToolFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = "native",
         description = "Compile the project to a GraalVM native image.")
final class NativeCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Option(names = "--buildpack",
            description = "Use Spring Boot Buildpacks (requires Docker) instead of local native-image.")
    private boolean buildpack;

    @Option(names = "--module", paramLabel = "<module>",
            description = "Module to build (multi-module projects).")
    private String module;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path cwd = parent.effectiveCwd();

        var projectOpt = ProjectDetector.detect(cwd);
        if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
        var project = projectOpt.get();
        Path root = module != null ? project.root().resolve(module) : project.root();

        String mvn = ToolFinder.find("mvn").map(Path::toString).orElse("mvn");

        // ── Buildpack path ────────────────────────────────────────────────────
        if (buildpack) {
            return runBuildpack(out, root, mvn);
        }

        // ── Local native-image path ───────────────────────────────────────────
        Path graalvmHome = resolveGraalvmHome(out);
        if (graalvmHome == null) {
            out.fail("No GraalVM installation found.");
            out.info("Install one with:  jolt jdk install 21 --distribution graalvm");
            return 1;
        }
        out.info("Using GraalVM: " + graalvmHome);

        List<String> cmd = new ArrayList<>(List.of(mvn, "-Pnative", "native:compile",
                "--batch-mode", "-pl", module != null ? module : "."));
        if (module != null) cmd.add("-am");

        // Set JAVA_HOME and prepend bin to PATH
        Map<String, String> extraEnv = new HashMap<>();
        extraEnv.put("JAVA_HOME", graalvmHome.toString());
        String existingPath = System.getenv("PATH");
        String binDir = graalvmHome.resolve("bin").toString();
        extraEnv.put("PATH", existingPath == null ? binDir : binDir + ":" + existingPath);

        out.info("Running: " + String.join(" ", cmd));
        try {
            long start = System.currentTimeMillis();
            int code = streamWithProgress(root, cmd, extraEnv, out);
            if (code != 0) { out.fail("Native compilation failed (exit " + code + ")"); return 1; }

            // Find the produced binary
            Path targetDir = root.resolve("target");
            Path binary = findNativeBinary(targetDir, project.artifactId());
            if (binary != null) {
                long sizeBytes = Files.size(binary);
                double sizeMb = sizeBytes / (1024.0 * 1024.0);
                long elapsed  = (System.currentTimeMillis() - start) / 1000;
                out.success(String.format("Binary: %s  (%.1f MB)  built in %ds",
                        binary.toAbsolutePath(), sizeMb, elapsed));
            } else {
                out.success("Native compilation succeeded.");
            }
            return 0;
        } catch (Exception e) {
            out.fail("native failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    // ── Buildpack ─────────────────────────────────────────────────────────────
    private int runBuildpack(Output out, Path root, String mvn) {
        // Check Docker daemon
        try {
            var check = ProcessRunner.capture(root, "docker", "info");
            if (!check.succeeded()) {
                out.fail("Docker daemon is not reachable. Start Docker and retry.");
                return 1;
            }
        } catch (Exception e) {
            out.fail("Docker not found or not running: " + e.getMessage());
            return 1;
        }

        List<String> cmd = List.of(mvn, "spring-boot:build-image", "-Pnative", "--batch-mode");
        out.info("Running: " + String.join(" ", cmd));
        try {
            int code = ProcessRunner.run(root, cmd);
            if (code != 0) { out.fail("Buildpack build failed (exit " + code + ")"); return 1; }
            out.success("Buildpack native image built successfully.");
            return 0;
        } catch (Exception e) {
            out.fail("Buildpack build failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    // ── GraalVM detection ─────────────────────────────────────────────────────
    private static Path resolveGraalvmHome(Output out) {
        // 1. Check active JDK: java.vendor.version contains "GraalVM"
        String vendorVersion = System.getProperty("java.vendor.version", "");
        String javaHome = System.getenv("JAVA_HOME");
        if (vendorVersion.toLowerCase().contains("graalvm")
                || vendorVersion.toLowerCase().contains("liberica-nik")
                || vendorVersion.toLowerCase().contains("mandrel")) {
            Path home = javaHome != null ? Path.of(javaHome)
                    : Path.of(System.getProperty("java.home", ""));
            if (Files.exists(home.resolve("bin").resolve("native-image"))) {
                return home;
            }
        }
        // Also check native-image in active JAVA_HOME regardless of vendor string
        if (javaHome != null) {
            Path ni = Path.of(javaHome, "bin", "native-image");
            if (Files.exists(ni)) return Path.of(javaHome);
        }

        // 2. Scan ~/.jolt/jdks/ for a GraalVM installation
        Path joltJdks = Path.of(System.getProperty("user.home"), ".jolt", "jdks");
        if (Files.isDirectory(joltJdks)) {
            try (var stream = Files.list(joltJdks)) {
                var found = stream
                        .filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString().toLowerCase().contains("graalvm")
                                  || Files.exists(d.resolve("bin").resolve("native-image")))
                        .filter(d -> Files.exists(d.resolve("bin").resolve("native-image")))
                        .findFirst();
                if (found.isPresent()) {
                    out.verbose("Found GraalVM in ~/.jolt/jdks/: " + found.get());
                    return found.get();
                }
            } catch (IOException ignored) {}
        }

        // 3. Check /usr/lib/jvm/* on Linux
        Path usrJvm = Path.of("/usr/lib/jvm");
        if (Files.isDirectory(usrJvm)) {
            try (var stream = Files.list(usrJvm)) {
                var found = stream
                        .filter(Files::isDirectory)
                        .filter(d -> Files.exists(d.resolve("bin").resolve("native-image")))
                        .findFirst();
                if (found.isPresent()) return found.get();
            } catch (IOException ignored) {}
        }

        return null;
    }

    // ── Streaming with progress ────────────────────────────────────────────────
    private int streamWithProgress(Path root, List<String> cmd,
                                   Map<String, String> extraEnv,
                                   Output out) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(true);
        pb.environment().putAll(extraEnv);

        Process proc = pb.start();
        long start = System.currentTimeMillis();

        var done = new AtomicBoolean(false);
        Thread progressThread = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted() && !done.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                if (!done.get()) {
                    long s = (System.currentTimeMillis() - start) / 1000;
                    System.err.printf("\r[%3ds] Building native image...", s);
                    System.err.flush();
                }
            }
        });

        try (var reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!out.isQuiet()) System.out.println(line);
            }
        }

        int code = proc.waitFor();
        done.set(true);
        progressThread.interrupt();
        progressThread.join(500);
        System.err.print("\r                                                  \r");
        System.err.flush();
        return code;
    }

    // ── Find native binary in target/ ─────────────────────────────────────────
    private static Path findNativeBinary(Path targetDir, String artifactId) {
        if (!Files.isDirectory(targetDir)) return null;
        try (var stream = Files.list(targetDir)) {
            return stream
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> !p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith(".xml"))
                    .filter(p -> !p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> !p.getFileName().toString().startsWith("original-"))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.equals(artifactId.toLowerCase())
                            || name.startsWith(artifactId.toLowerCase() + "-")
                            || name.startsWith(artifactId.toLowerCase() + ".");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
