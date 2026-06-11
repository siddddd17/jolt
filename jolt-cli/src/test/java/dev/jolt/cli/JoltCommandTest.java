package dev.jolt.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class JoltCommandTest {

    private CommandLine cli() {
        return new CommandLine(new Jolt())
            .setCaseInsensitiveEnumValuesAllowed(true);
    }

    @Test
    void helpExitsZero() {
        assertEquals(0, cli().execute("--help"));
    }

    @Test
    void versionExitsZero() {
        assertEquals(0, cli().execute("--version"));
    }

    @Test
    void allSubcommandsRegistered() {
        var cmd = cli();
        var subs = cmd.getSubcommands().keySet();
        assertTrue(subs.contains("doctor"),  "missing: doctor");
        assertTrue(subs.contains("new"),     "missing: new");
        assertTrue(subs.contains("run"),     "missing: run");
        assertTrue(subs.contains("test"),    "missing: test");
        assertTrue(subs.contains("deps"),    "missing: deps");
        assertTrue(subs.contains("docker"),  "missing: docker");
        assertTrue(subs.contains("ci"),      "missing: ci");
        assertTrue(subs.contains("info"),    "missing: info");
        assertTrue(subs.contains("clean"),   "missing: clean");
        assertTrue(subs.contains("jdk"),     "missing: jdk");
    }

    @Test
    void unknownSubcommandExitsNonZero() {
        assertNotEquals(0, cli().execute("nonexistent-command"));
    }

    @Test
    void globalFlagsInherited() {
        var jolt = new Jolt();
        var cmd = new CommandLine(jolt);
        cmd.execute("--quiet", "--json", "doctor");
        assertTrue(jolt.quiet);
        assertTrue(jolt.json);
    }
}
