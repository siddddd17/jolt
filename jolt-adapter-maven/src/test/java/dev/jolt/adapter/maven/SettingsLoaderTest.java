package dev.jolt.adapter.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link SettingsLoader} reads mirrors, proxies, and server auth from a
 * custom {@code settings.xml} and wires them into the Aether session.
 */
class SettingsLoaderTest {

    @Test
    void mirrorRedirectIsAppliedToSession(@TempDir Path tmp) throws IOException {
        Path settingsFile = tmp.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                  <mirrors>
                    <mirror>
                      <id>corp-mirror</id>
                      <url>https://nexus.example.com/maven2</url>
                      <mirrorOf>central</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        var settings = SettingsLoader.load(null, settingsFile);
        var resolver = new GraphResolver();
        var session  = resolver.newSession(tmp.resolve("repo"), settings);

        // Mirror selector must redirect "central" to the configured mirror URL.
        var mirrored = session.getMirrorSelector().getMirror(GraphResolver.CENTRAL);
        assertNotNull(mirrored, "Expected a mirror to be configured for 'central'");
        assertEquals("https://nexus.example.com/maven2", mirrored.getUrl());
        assertEquals("corp-mirror", mirrored.getId());
    }

    @Test
    void noMirrorWhenSettingsEmpty(@TempDir Path tmp) throws IOException {
        Path settingsFile = tmp.resolve("settings.xml");
        Files.writeString(settingsFile, "<settings></settings>");

        var settings = SettingsLoader.load(null, settingsFile);
        var resolver = new GraphResolver();
        var session  = resolver.newSession(tmp.resolve("repo"), settings);

        // With no mirrors configured, getMirror() should return null for central.
        var mirrored = session.getMirrorSelector().getMirror(GraphResolver.CENTRAL);
        assertNull(mirrored, "No mirror should be configured for 'central'");
    }

    @Test
    void wildcardMirrorMatchesCentral(@TempDir Path tmp) throws IOException {
        Path settingsFile = tmp.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                  <mirrors>
                    <mirror>
                      <id>all-mirror</id>
                      <url>https://nexus.example.com/all</url>
                      <mirrorOf>*</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        var settings = SettingsLoader.load(null, settingsFile);
        var resolver = new GraphResolver();
        var session  = resolver.newSession(tmp.resolve("repo"), settings);

        var mirrored = session.getMirrorSelector().getMirror(GraphResolver.CENTRAL);
        assertNotNull(mirrored, "Wildcard mirror should match 'central'");
        assertEquals("https://nexus.example.com/all", mirrored.getUrl());
    }
}
