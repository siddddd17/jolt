package dev.jolt.core.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ProcessRunner {

    private ProcessRunner() {}

    /**
     * Runs a command inheriting this process's stdout/stderr.
     * Use for long-running interactive commands (run, test, clean).
     */
    public static int run(Path workDir, List<String> command) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .inheritIO();
        return pb.start().waitFor();
    }

    public static int run(Path workDir, String... command) throws IOException, InterruptedException {
        return run(workDir, List.of(command));
    }

    /**
     * Runs a command and captures its combined stdout+stderr.
     * Use for detection/inspection commands (version checks, etc.).
     */
    public static Result capture(Path workDir, List<String> command) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes()).trim();
        int code = p.waitFor();
        return new Result(code, out);
    }

    public static Result capture(Path workDir, String... command) throws IOException, InterruptedException {
        return capture(workDir, List.of(command));
    }

    public record Result(int exitCode, String output) {
        public boolean succeeded() { return exitCode == 0; }
        public String firstLine() {
            int nl = output.indexOf('\n');
            return nl < 0 ? output : output.substring(0, nl);
        }
    }
}
