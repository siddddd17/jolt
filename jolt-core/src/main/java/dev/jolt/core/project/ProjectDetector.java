package dev.jolt.core.project;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ProjectDetector {

    private ProjectDetector() {}

    public static Optional<ProjectInfo> detect(Path start) {
        Path candidate = start.toAbsolutePath().normalize();
        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.exists(pom)) return Optional.of(parseMaven(pom));

            Path gradle = candidate.resolve("build.gradle");
            Path gradleKts = candidate.resolve("build.gradle.kts");
            if (Files.exists(gradle) || Files.exists(gradleKts)) {
                return Optional.of(minimalGradle(candidate));
            }
            candidate = candidate.getParent();
        }
        return Optional.empty();
    }

    private static ProjectInfo parseMaven(Path pomPath) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            var doc = dbf.newDocumentBuilder().parse(pomPath.toFile());
            doc.getDocumentElement().normalize();

            // Read groupId/artifactId/version from direct children of <project>, not from <parent>
            var root = doc.getDocumentElement();
            String groupId    = directChildText(root, "groupId");
            String artifactId = directChildText(root, "artifactId");
            String version    = directChildText(root, "version");
            String packaging  = directChildText(root, "packaging");
            if (packaging == null) packaging = "jar";

            // Inherit groupId from parent if not declared at project level
            if (groupId == null) {
                NodeList parents = root.getElementsByTagName("parent");
                if (parents.getLength() > 0) {
                    groupId = directChildText((org.w3c.dom.Element) parents.item(0), "groupId");
                }
            }

            boolean multiModule = doc.getElementsByTagName("modules").getLength() > 0;
            boolean springBoot  = xmlContains(doc, "dependency", "groupId", "org.springframework.boot");
            boolean sbPlugin    = xmlContains(doc, "plugin",     "artifactId", "spring-boot-maven-plugin");

            return new ProjectInfo(
                pomPath.getParent(), BuildSystem.MAVEN,
                groupId, artifactId, version, packaging,
                springBoot, sbPlugin, multiModule
            );
        } catch (Exception e) {
            return new ProjectInfo(pomPath.getParent(), BuildSystem.MAVEN,
                null, pomPath.getParent().getFileName().toString(), "unknown", "jar",
                false, false, false);
        }
    }

    private static String directChildText(org.w3c.dom.Element parent, String tagName) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var node = children.item(i);
            if (tagName.equals(node.getNodeName())) {
                String text = node.getTextContent();
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        return null;
    }

    private static ProjectInfo minimalGradle(Path root) {
        return new ProjectInfo(root, BuildSystem.GRADLE,
            null, root.getFileName().toString(), "unknown", "jar",
            false, false, false);
    }

    private static boolean xmlContains(Document doc, String elementTag, String childTag, String value) {
        NodeList elements = doc.getElementsByTagName(elementTag);
        for (int i = 0; i < elements.getLength(); i++) {
            NodeList children = elements.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                var child = children.item(j);
                if (childTag.equals(child.getNodeName()) &&
                    value.equals(child.getTextContent().trim())) return true;
            }
        }
        return false;
    }

    /** Counts source files with a given extension under src/test. */
    public static int countTestClasses(Path root) {
        Path testSrc = root.resolve("src/test/java");
        if (!Files.exists(testSrc)) return 0;
        try (var stream = Files.walk(testSrc)) {
            return (int) stream.filter(p -> p.toString().endsWith(".java")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Counts source files under src/main/java. */
    public static int countSourceFiles(Path root) {
        Path mainSrc = root.resolve("src/main/java");
        if (!Files.exists(mainSrc)) return 0;
        try (var stream = Files.walk(mainSrc)) {
            return (int) stream.filter(p -> p.toString().endsWith(".java")).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
