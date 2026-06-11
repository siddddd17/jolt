package dev.jolt.cli;

/**
 * All user-visible output flows through this abstraction.
 * Respects --quiet, --verbose, --json, --no-color, and the NO_COLOR env var.
 */
public final class Output {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final boolean quiet;
    private final boolean verbose;
    private final boolean json;
    private final boolean noColor;

    public Output(boolean quiet, boolean verbose, boolean json, boolean noColor) {
        this.quiet = quiet;
        this.verbose = verbose;
        this.json = json;
        this.noColor = noColor || System.getenv("NO_COLOR") != null;
    }

    public void info(String message) {
        if (!quiet) System.out.println(message);
    }

    public void verbose(String message) {
        if (verbose) System.out.println(message);
    }

    public void success(String message) {
        if (!quiet) System.out.println(color(GREEN, "✓") + " " + message);
    }

    public void warn(String message) {
        if (!quiet) System.out.println(color(YELLOW, "⚠") + " " + message);
    }

    public void fail(String message) {
        System.err.println(color(RED, "✗") + " " + message);
    }

    public void error(String message) {
        System.err.println(message);
    }

    public boolean isJson() { return json; }
    public boolean isQuiet() { return quiet; }
    public boolean isVerbose() { return verbose; }

    private String color(String ansi, String text) {
        return noColor ? text : ansi + text + RESET;
    }
}
