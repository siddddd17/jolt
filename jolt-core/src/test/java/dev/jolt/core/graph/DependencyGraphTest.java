package dev.jolt.core.graph;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {

    @Test
    void emptyGraph() {
        var graph = DependencyGraph.empty();
        assertEquals(0, graph.size());
        assertFalse(graph.hasConflicts());
        assertTrue(graph.roots().isEmpty());
    }

    @Test
    void gavParsing() {
        var gav = GAV.parse("org.springframework:spring-core:6.1.0");
        assertEquals("org.springframework", gav.groupId());
        assertEquals("spring-core", gav.artifactId());
        assertEquals("6.1.0", gav.version());
        assertEquals("org.springframework:spring-core", gav.ga().toString());
    }

    @Test
    void gaParsing() {
        var ga = GA.parse("com.example:my-lib");
        assertEquals("com.example", ga.groupId());
        assertEquals("my-lib", ga.artifactId());
    }

    @Test
    void scopeResolution() {
        assertEquals(Scope.COMPILE, Scope.of(null));
        assertEquals(Scope.TEST, Scope.of("test"));
        assertEquals(Scope.PROVIDED, Scope.of("PROVIDED"));
    }

    @Test
    void conflictDescribesVersions() {
        var conflict = new Conflict(
                GA.parse("com.example:lib"),
                java.util.List.of("1.0", "2.0"),
                "2.0");
        assertEquals("2.0", conflict.selectedVersion());
        assertEquals(2, conflict.requestedVersions().size());
    }
}
