package dev.jolt.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 acceptance-criteria tests.
 *
 * Verifies:
 *  1. jolt --help lists every required subcommand (fat JAR)
 *  2. jolt --version works (fat JAR)
 *  3. Both pass when run as a native binary (if present)
 */
class Phase0IT {

    private static Path jarPath;
    private static Path nativePath;

    private static final List<String> REQUIRED_SUBCOMMANDS = List.of(
        "doctor", "new", "run", "test", "deps", "docker", "ci", "info", "clean", "jdk"
    );

    @BeforeAll
    static void resolveArtifacts() {
        jarPath    = Path.of(System.getProperty("jolt.jar",    ""));
        nativePath = Path.of(System.getProperty("jolt.native", ""));
    }

    // --- Fat JAR tests ---

    @Test
    void jarExists() {
        assertTrue(Files.exists(jarPath), "Fat JAR not found at: " + jarPath);
    }

    @Test
    void jarHelpExitsZero() throws Exception {
        var result = run("java", "-jar", jarPath.toString(), "--help");
        assertEquals(0, result.exitCode(), "--help exit code (jar)\n" + result.output());
    }

    @Test
    void jarHelpListsAllSubcommands() throws Exception {
        var result = run("java", "-jar", jarPath.toString(), "--help");
        String out = result.output();
        for (String sub : REQUIRED_SUBCOMMANDS) {
            assertTrue(out.contains(sub), "--help output missing subcommand '" + sub + "'\n" + out);
        }
    }

    @Test
    void jarVersionExitsZero() throws Exception {
        var result = run("java", "-jar", jarPath.toString(), "--version");
        assertEquals(0, result.exitCode(), "--version exit code (jar)\n" + result.output());
    }

    @Test
    void jarVersionOutputContainsJolt() throws Exception {
        var result = run("java", "-jar", jarPath.toString(), "--version");
        assertTrue(result.output().contains("jolt"),
            "--version output should contain 'jolt'\n" + result.output());
    }

    // --- Native binary tests (skipped when binary absent) ---

    @Test
    @DisabledIf("nativeMissing")
    void nativeHelpExitsZero() throws Exception {
        var result = run(nativePath.toString(), "--help");
        assertEquals(0, result.exitCode(), "native --help exit code\n" + result.output());
    }

    @Test
    @DisabledIf("nativeMissing")
    void nativeHelpListsAllSubcommands() throws Exception {
        var result = run(nativePath.toString(), "--help");
        String out = result.output();
        for (String sub : REQUIRED_SUBCOMMANDS) {
            assertTrue(out.contains(sub), "native --help missing '" + sub + "'\n" + out);
        }
    }

    @Test
    @DisabledIf("nativeMissing")
    void nativeVersionExitsZero() throws Exception {
        var result = run(nativePath.toString(), "--version");
        assertEquals(0, result.exitCode(), "native --version exit code\n" + result.output());
    }

    // --- Helpers ---

    static boolean nativeMissing() {
        return !Files.exists(nativePath) || !nativePath.toFile().canExecute();
    }

    private record RunResult(int exitCode, String output) {}

    private RunResult run(String... command) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        var p  = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        return new RunResult(code, out);
    }
}
