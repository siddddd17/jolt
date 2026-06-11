package dev.jolt.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code deps add} omits the {@code <version>} element when a BOM in
 * {@code <dependencyManagement>} already governs the dependency.
 *
 * <p>This is the correctness case most implementations get wrong: blindly inserting
 * {@code <version>MANAGED</version>} would break BOM-managed version resolution.
 */
class BomVersionOmissionTest {

    /** Minimal pom.xml that imports spring-boot-dependencies as a BOM. */
    private static final String FIXTURE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>bom-test</artifactId>
              <version>1.0-SNAPSHOT</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>3.2.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void addWithoutVersionDoesNotInsertVersionTag(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, FIXTURE_POM, StandardCharsets.UTF_8);

        // Add spring-boot-starter-actuator without specifying a version.
        // The BOM governs the version, so <version> must NOT appear in the inserted block.
        DepsCommand.AddCommand.insertDependency(pom,
                "org.springframework.boot", "spring-boot-starter-actuator",
                null,   // ← no version: let BOM manage it
                null,   // ← default scope (compile), omitted
                false); // ← not optional

        String result = Files.readString(pom, StandardCharsets.UTF_8);

        // Verify the dependency was inserted
        assertTrue(result.contains("<artifactId>spring-boot-starter-actuator</artifactId>"),
                "Expected <artifactId>spring-boot-starter-actuator</artifactId> in pom.xml");

        // The critical assertion: no <version> tag for the newly inserted dependency.
        // We check the block immediately around the inserted artifact.
        int blockStart = result.indexOf("<artifactId>spring-boot-starter-actuator</artifactId>");
        assertTrue(blockStart >= 0, "Inserted dependency not found");

        // Find the enclosing <dependency>…</dependency> block
        int depStart = result.lastIndexOf("<dependency>", blockStart);
        int depEnd   = result.indexOf("</dependency>", blockStart) + "</dependency>".length();
        assertTrue(depStart >= 0 && depEnd > blockStart, "Could not locate dependency block");
        String block = result.substring(depStart, depEnd);

        assertFalse(block.contains("<version>"),
                "BOM-managed dependency must NOT contain a <version> tag.\n"
                + "Inserted block:\n" + block);
    }

    @Test
    void addWithExplicitVersionDoesInsertVersionTag(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, FIXTURE_POM, StandardCharsets.UTF_8);

        DepsCommand.AddCommand.insertDependency(pom,
                "org.projectlombok", "lombok",
                "1.18.30",  // ← explicit version
                "provided",
                false);

        String result = Files.readString(pom, StandardCharsets.UTF_8);
        assertTrue(result.contains("<version>1.18.30</version>"),
                "Explicitly versioned dependency must include <version>");
        assertTrue(result.contains("<scope>provided</scope>"),
                "Non-compile scope must be written");
    }
}
