package dev.jolt.it;

import dev.jolt.adapter.maven.GraphResolver;
import dev.jolt.core.lock.LockfileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that resolving the spring-boot fixture twice in the same JVM produces
 * byte-identical jolt.lock content (modulo generated_at timestamp).
 * Catches non-determinism from HashMap iteration order, Aether sort instability, etc.
 */
class LockfileReproducibilityTest {

    @Test
    void twoResolutionsProduceByteIdenticalLock(@TempDir Path tmp) throws Exception {
        Path fixture = Path.of(System.getProperty(
                "jolt.fixtures.springboot", "jolt-it/fixtures/spring-boot"));
        Path localRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");

        var resolver = new GraphResolver();

        // First resolution
        var session1 = resolver.newSession(localRepo);
        var graph1 = resolver.resolve(fixture, session1, List.of(GraphResolver.CENTRAL)).graph();
        var lockfile1 = LockfileWriter.fromGraph(graph1, "fixture-app");
        Path out1 = tmp.resolve("jolt1.lock");
        LockfileWriter.write(lockfile1, out1);

        // Second resolution — new session, same JVM
        var session2 = resolver.newSession(localRepo);
        var graph2 = resolver.resolve(fixture, session2, List.of(GraphResolver.CENTRAL)).graph();
        var lockfile2 = LockfileWriter.fromGraph(graph2, "fixture-app");
        Path out2 = tmp.resolve("jolt2.lock");
        LockfileWriter.write(lockfile2, out2);

        // Strip the always-differing timestamp before comparing
        String s1 = normalize(Files.readString(out1, StandardCharsets.UTF_8));
        String s2 = normalize(Files.readString(out2, StandardCharsets.UTF_8));

        assertEquals(s1, s2,
                "Two resolutions of the same fixture must produce byte-identical jolt.lock");
    }

    private static String normalize(String content) {
        return content.replaceAll("generated_at = \"[^\"]+\"", "generated_at = \"<redacted>\"");
    }
}
