package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command(name = "jdk",
         description = "Manage JDK installations and project toolchain pinning.",
         subcommands = {
             JdkCommand.ListCommand.class,
             JdkCommand.InstallCommand.class,
             JdkCommand.UseCommand.class
         })
final class JdkCommand implements Callable<Integer> {

    @ParentCommand
    Jolt parent;

    @Override
    public Integer call() {
        new Output(parent.quiet, parent.verbose, parent.json, parent.noColor)
            .info("Usage: jolt jdk <subcommand>  (list | install | use)");
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // list
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "list", description = "List JDKs discoverable by Jolt.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand JdkCommand jdk;

        @Override
        public Integer call() {
            var out = new Output(jdk.parent.quiet, jdk.parent.verbose,
                                 jdk.parent.json, jdk.parent.noColor);
            var found = discoverJdks();
            if (found.isEmpty()) {
                out.warn("No JDKs found. Install one with: jolt jdk install <version>");
                return 0;
            }
            out.info("Discovered JDKs:\n");
            for (var entry : found) {
                out.info("  " + entry.versionString() + "  " + entry.home());
            }
            return 0;
        }

        /** Scan well-known locations and return discovered JDKs (deduped by home path). */
        static List<JdkEntry> discoverJdks() {
            var candidates = new LinkedHashSet<Path>();

            // 1. JAVA_HOME
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome));

            // 2. Resolve `java` on PATH → back up to home dir
            Optional.ofNullable(System.getenv("PATH"))
                .stream()
                .flatMap(p -> Arrays.stream(p.split(File.pathSeparator)))
                .map(dir -> Path.of(dir, "java"))
                .filter(Files::exists)
                .map(javaBin -> {
                    try { return javaBin.toRealPath(); } catch (IOException e) { return javaBin; }
                })
                .map(p -> p.getParent() != null ? p.getParent().getParent() : null)
                .filter(Objects::nonNull)
                .filter(Files::isDirectory)
                .forEach(candidates::add);

            // 3. /usr/lib/jvm/* (Linux)
            scanDir(Path.of("/usr/lib/jvm"), candidates);

            // 4. ~/.jolt/jdks/* (Jolt-managed)
            scanDir(Path.of(System.getProperty("user.home"), ".jolt", "jdks"), candidates);

            // 5. ~/.sdkman/candidates/java/*
            scanDir(Path.of(System.getProperty("user.home"), ".sdkman", "candidates", "java"), candidates);

            // 6. ~/.jenv/versions/*
            scanDir(Path.of(System.getProperty("user.home"), ".jenv", "versions"), candidates);

            // Probe each candidate
            var results = new ArrayList<JdkEntry>();
            var seen    = new LinkedHashSet<Path>();
            for (var home : candidates) {
                Path javaBin = home.resolve("bin").resolve("java");
                if (!Files.exists(javaBin)) continue;
                try {
                    Path real = home.toRealPath();
                    if (!seen.add(real)) continue;
                } catch (IOException e) {
                    if (!seen.add(home)) continue;
                }
                String ver = queryJavaVersion(javaBin);
                if (ver != null) results.add(new JdkEntry(home, ver));
            }
            return results;
        }

        private static void scanDir(Path dir, Set<Path> out) {
            if (!Files.isDirectory(dir)) return;
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).forEach(out::add);
            } catch (IOException ignored) {}
        }

        static String queryJavaVersion(Path javaBin) {
            try {
                var pb = new ProcessBuilder(javaBin.toString(), "-version");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                proc.waitFor();
                // `java -version` writes to stderr; first line: openjdk version "21.0.3" 2024-04-16
                var m = Pattern.compile("[\"](\\S+)[\"]").matcher(output);
                return m.find() ? m.group(1) : output.lines().findFirst().orElse(null);
            } catch (Exception e) {
                return null;
            }
        }

        record JdkEntry(Path home, String versionString) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // install
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "install", description = "Download and install a JDK via Foojay Disco API.")
    static final class InstallCommand implements Callable<Integer> {
        @ParentCommand JdkCommand jdk;

        @Parameters(index = "0", paramLabel = "<version>",
                    description = "Major version (e.g. 21) or full version (e.g. 21.0.3)")
        String version;

        @Option(names = "--distribution", paramLabel = "<dist>",
                description = "Distribution: temurin (default), graalvm, zulu",
                defaultValue = "temurin")
        String distribution;

        @Override
        public Integer call() {
            var out = new Output(jdk.parent.quiet, jdk.parent.verbose,
                                 jdk.parent.json, jdk.parent.noColor);

            String os   = detectOs();
            String arch = detectArch();
            out.info("Installing JDK " + version + " (" + distribution + ") for "
                    + os + "/" + arch + "…");

            try {
                // ── Query Foojay Disco API ───────────────────────────────────
                String foojayUrl = "https://api.foojay.io/disco/v3.0/packages"
                        + "?version=" + version
                        + "&distribution=" + distribution
                        + "&architecture=" + arch
                        + "&package_type=jdk"
                        + "&operating_system=" + os
                        + "&latest=available"
                        + "&directly_downloadable=true";

                var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                var apiResp = client.send(
                        HttpRequest.newBuilder().uri(URI.create(foojayUrl))
                                .timeout(Duration.ofSeconds(15))
                                .header("User-Agent", "jolt-cli/0.1")
                                .GET().build(),
                        BodyHandlers.ofString());

                if (apiResp.statusCode() != 200) {
                    out.fail("Foojay API returned HTTP " + apiResp.statusCode());
                    return 1;
                }

                String pkg = extractFirstPackage(apiResp.body());
                if (pkg == null) {
                    out.fail("No package found for " + distribution + "-" + version
                            + " on " + os + "/" + arch
                            + ". Try a different --distribution or version.");
                    return 1;
                }

                String[] pkgParts   = pkg.split("\t");
                String   downloadUrl = pkgParts[0];
                String   filename    = pkgParts[1];
                String   javaVersion = pkgParts[2];

                // ── Download ────────────────────────────────────────────────
                Path installRoot = Path.of(System.getProperty("user.home"), ".jolt", "jdks");
                Files.createDirectories(installRoot);
                String dirName  = distribution + "-" + javaVersion;
                Path targetDir  = installRoot.resolve(dirName);
                if (Files.exists(targetDir)) {
                    out.warn(distribution + "-" + javaVersion
                            + " is already installed at " + targetDir);
                    return 0;
                }

                Path tmpFile = installRoot.resolve(filename + ".part");
                out.info("Downloading " + filename + "…");
                downloadWithProgress(client, downloadUrl, tmpFile, out);

                // ── Extract ─────────────────────────────────────────────────
                out.info("Extracting…");
                Files.createDirectories(targetDir);
                extract(tmpFile, targetDir, out);
                Files.deleteIfExists(tmpFile);

                out.success("Installed " + distribution + "-" + javaVersion
                        + " → " + targetDir);
                out.info("Run 'jolt jdk use " + majorVersion(javaVersion)
                        + "' to pin this JDK to your project.");
                return 0;

            } catch (Exception e) {
                out.fail("Install failed: " + e.getMessage());
                if (jdk.parent.verbose) e.printStackTrace();
                return 1;
            }
        }

        /** Returns first package as {@code downloadUrl\tfilename\tjavaVersion}, or null. */
        private static String extractFirstPackage(String json) {
            // Find download redirect URL
            var dlPat  = Pattern.compile("\"pkg_download_redirect\"\\s*:\\s*\"([^\"]+)\"");
            var fnPat  = Pattern.compile("\"filename\"\\s*:\\s*\"([^\"]+)\"");
            var verPat = Pattern.compile("\"java_version\"\\s*:\\s*\"([^\"]+)\"");
            var dlm  = dlPat.matcher(json);
            var fnm  = fnPat.matcher(json);
            var verm = verPat.matcher(json);
            if (dlm.find() && fnm.find() && verm.find()) {
                return dlm.group(1) + "\t" + fnm.group(1) + "\t" + verm.group(1);
            }
            return null;
        }

        private static void downloadWithProgress(HttpClient client, String url,
                Path dest, Output out) throws IOException, InterruptedException {
            var resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofMinutes(10))
                            .header("User-Agent", "jolt-cli/0.1")
                            .GET().build(),
                    BodyHandlers.ofInputStream());

            long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
            try (var in = resp.body(); var out2 = Files.newOutputStream(dest)) {
                byte[] buf    = new byte[65_536];
                long   done   = 0;
                int    n;
                int    lastPct = -1;
                while ((n = in.read(buf)) >= 0) {
                    out2.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct && pct % 5 == 0) {
                            System.err.print("\r  [" + "=".repeat(pct / 5)
                                    + " ".repeat(20 - pct / 5) + "] " + pct + "%");
                            lastPct = pct;
                        }
                    }
                }
            }
            System.err.println(); // newline after progress bar
        }

        private static void extract(Path archive, Path targetDir, Output out)
                throws IOException, InterruptedException {
            String name = archive.getFileName().toString();
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                // Use system tar with --strip-components=1 to remove top-level dir.
                var pb = new ProcessBuilder("tar", "-xzf", archive.toString(),
                        "-C", targetDir.toString(), "--strip-components=1");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                if (proc.waitFor() != 0) {
                    String msg = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("tar extraction failed: " + msg);
                }
            } else if (name.endsWith(".zip")) {
                extractZip(archive, targetDir);
            } else {
                throw new IOException("Unknown archive format: " + name);
            }
        }

        private static void extractZip(Path zip, Path targetDir) throws IOException {
            try (var fs = FileSystems.newFileSystem(zip, (ClassLoader) null)) {
                var root = fs.getRootDirectories().iterator().next();
                // Find the single top-level directory to strip it.
                Path topLevel = null;
                try (var roots = Files.list(root)) {
                    topLevel = roots.filter(Files::isDirectory).findFirst().orElse(null);
                }
                final Path base = topLevel != null ? topLevel : root;
                try (var walk = Files.walk(base)) {
                    for (var src : (Iterable<Path>) walk::iterator) {
                        Path rel  = base.relativize(src);
                        Path dest = targetDir.resolve(rel.toString());
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.createDirectories(dest.getParent());
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }

        static String detectOs() {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac"))     return "mac";
            if (os.contains("windows")) return "windows";
            return "linux";
        }

        static String detectArch() {
            return switch (System.getProperty("os.arch", "amd64")) {
                case "aarch64", "arm64" -> "aarch64";
                default                 -> "x64";
            };
        }

        static String majorVersion(String version) {
            return version.split("[.+]")[0];
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // use
    // ─────────────────────────────────────────────────────────────────────────
    @Command(name = "use",
             description = "Pin the project's JDK to <version> by writing .java-version.")
    static final class UseCommand implements Callable<Integer> {
        @ParentCommand JdkCommand jdk;

        @Parameters(index = "0", paramLabel = "<version>",
                    description = "Major version to pin (e.g. 21)")
        String version;

        @Override
        public Integer call() {
            var out = new Output(jdk.parent.quiet, jdk.parent.verbose,
                                 jdk.parent.json, jdk.parent.noColor);
            Path cwd = jdk.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            Path root = projectOpt.map(p -> p.root()).orElse(cwd);

            Path marker = root.resolve(".java-version");
            try {
                Files.writeString(marker, version + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                out.success("Pinned JDK " + version + " (wrote " + marker + ")");
                out.info("Run 'jolt doctor' to verify the active JDK matches.");
            } catch (IOException e) {
                out.fail("Could not write " + marker + ": " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }
}
