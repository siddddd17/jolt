package dev.jolt.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code deps remove} is format-preserving: add + remove yields an
 * identical pom, and surrounding dependencies/comments are untouched.
 */
class DepsRemoveTest {

    private static final String BASE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0-SNAPSHOT</version>
              <dependencies>
                <dependency>
                  <groupId>org.apache.commons</groupId>
                  <artifactId>commons-lang3</artifactId>
                  <version>3.14.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    /** Add lombok then remove it — pom must be byte-identical to original. */
    @Test
    void addThenRemoveRestoresOriginalPom(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, BASE_POM, StandardCharsets.UTF_8);

        // Add lombok (simulates deps add)
        DepsCommand.AddCommand.insertDependency(pom,
                "org.projectlombok", "lombok", "1.18.32", "provided", false);

        String afterAdd = Files.readString(pom, StandardCharsets.UTF_8);
        assertTrue(afterAdd.contains("<artifactId>lombok</artifactId>"),
                "lombok should be present after add");

        // Remove lombok (simulates deps remove)
        String afterRemove = DepsCommand.RemoveCommand.removeDependency(
                afterAdd, "org.projectlombok", "lombok");
        assertNotNull(afterRemove, "removeDependency should find the block");
        Files.writeString(pom, afterRemove, StandardCharsets.UTF_8);

        // pom must be byte-identical to the original
        String result = Files.readString(pom, StandardCharsets.UTF_8);
        assertFalse(result.contains("<artifactId>lombok</artifactId>"),
                "lombok must be gone");
        assertTrue(result.contains("<artifactId>commons-lang3</artifactId>"),
                "commons-lang3 must still be present");
        assertEquals(BASE_POM, result,
                "pom must be byte-identical to the original after add+remove");
    }

    /** Remove returns null when the artifact is not present. */
    @Test
    void removeReturnsNullWhenNotFound() {
        String result = DepsCommand.RemoveCommand.removeDependency(
                BASE_POM, "org.example", "nonexistent");
        assertNull(result, "removeDependency must return null for a missing artifact");
    }

    /** Remove does not touch <dependencyManagement> BOM imports. */
    @Test
    void removeSkipsDependencyManagementSection() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.32</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-lang3</artifactId>
                      <version>3.14.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        // lombok is only in <dependencyManagement>, not in <dependencies> — should return null.
        String result = DepsCommand.RemoveCommand.removeDependency(
                pom, "org.projectlombok", "lombok");
        assertNull(result, "Should not remove from dependencyManagement");
    }

    /** When two deps share an artifactId, groupId disambiguates correctly. */
    @Test
    void removeDisambiguatesByGroupId() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example.a</groupId>
                      <artifactId>shared</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example.b</groupId>
                      <artifactId>shared</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DepsCommand.RemoveCommand.removeDependency(pom, "com.example.a", "shared");
        assertNotNull(result);
        assertFalse(result.contains("com.example.a"), "com.example.a:shared must be gone");
        assertTrue(result.contains("com.example.b"), "com.example.b:shared must remain");
    }
}
