package dev.jolt.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(
    name = Jolt.NAME,
    mixinStandardHelpOptions = true,
    versionProvider = JoltVersionProvider.class,
    description = "Opinionated developer tooling for Java.",
    subcommands = {
        DoctorCommand.class,
        NewCommand.class,
        RunCommand.class,
        TestCommand.class,
        DepsCommand.class,
        DockerCommand.class,
        CiCommand.class,
        InfoCommand.class,
        CleanCommand.class,
        JdkCommand.class,
        SbomCommand.class,
        NativeCommand.class,
        MigrateCommand.class,
        picocli.AutoComplete.GenerateCompletion.class
    })
public final class Jolt implements Runnable {

    /** The one place the binary name lives. Rename here only. */
    public static final String NAME = "jolt";

    @Option(names = "--quiet",    scope = ScopeType.INHERIT, description = "Suppress non-essential output.")
    boolean quiet;

    @Option(names = "--verbose",  scope = ScopeType.INHERIT, description = "Enable verbose output.")
    boolean verbose;

    @Option(names = "--json",     scope = ScopeType.INHERIT, description = "Emit machine-readable JSON output.")
    boolean json;

    @Option(names = "--no-color", scope = ScopeType.INHERIT, description = "Disable ANSI color output.")
    boolean noColor;

    @Option(names = "--cwd",      scope = ScopeType.INHERIT, paramLabel = "<dir>",
            description = "Set working directory for all operations.")
    String cwd;

    @Option(names = "--locked",   scope = ScopeType.INHERIT,
            description = "Resolve strictly from jolt.lock; fail (exit 4) if lock drifted or checksum mismatches.")
    boolean locked;

    /** Resolves the effective working directory (--cwd or JVM cwd). */
    public java.nio.file.Path effectiveCwd() {
        if (cwd != null && !cwd.isBlank()) return java.nio.file.Path.of(cwd).toAbsolutePath();
        return java.nio.file.Path.of(System.getProperty("user.dir"));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Jolt())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
        System.exit(code);
    }
}
