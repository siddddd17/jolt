package dev.jolt.cli;

import dev.jolt.adapter.maven.GraphResolver;
import dev.jolt.adapter.maven.ReactorDetector;
import dev.jolt.core.graph.GA;
import dev.jolt.core.graph.DependencyNode;
import dev.jolt.core.graph.GAV;
import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.project.ReactorGraph;
import org.cyclonedx.Version;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Metadata;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "sbom",
         description = "Generate a CycloneDX 1.5 SBOM for the project.")
final class SbomCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Option(names = "--format", paramLabel = "<json|xml>",
            description = "Output format (default: json).",
            defaultValue = "json")
    private String format;

    @Option(names = "--output", paramLabel = "<path>",
            description = "Output file path (default: bom.json or bom.xml in project root).")
    private String outputPath;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path cwd = parent.effectiveCwd();

        // Determine project root and GAV for metadata
        String groupId, artifactId, version;
        Path projectRoot;
        Map<GA, DependencyNode> allNodes;

        // Try multi-module reactor first
        Optional<Path> reactorRootOpt = ReactorDetector.findRoot(cwd);
        if (reactorRootOpt.isPresent()) {
            Path reactorRoot = reactorRootOpt.get();
            ReactorGraph reactor = ReactorDetector.scan(reactorRoot);

            if (!reactor.modules().isEmpty()) {
                // Use reactor root coordinates
                var rootModule = reactor.modules().stream()
                        .filter(m -> m.path().toString().equals("."))
                        .findFirst()
                        .orElse(reactor.modules().get(0));

                groupId    = rootModule.coordinates().groupId();
                artifactId = rootModule.coordinates().artifactId();
                version    = rootModule.coordinates().version();
                projectRoot = reactorRoot;

                // Collect and deduplicate external deps across all modules by GA
                var deduped = new LinkedHashMap<GA, DependencyNode>();
                var localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
                var resolver  = new GraphResolver();
                var session   = resolver.newSession(localRepo);
                for (var module : reactor.modules()) {
                    try {
                        var result = resolver.resolve(
                                reactorRoot.resolve(module.path()), session,
                                List.of(GraphResolver.CENTRAL));
                        for (var entry : result.graph().allNodes().entrySet()) {
                            deduped.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    } catch (Exception ignored) {}
                }
                allNodes = deduped;
            } else {
                return fallbackSingleModule(out, cwd);
            }
        } else {
            return fallbackSingleModule(out, cwd);
        }

        return generateSbom(out, groupId, artifactId, version, projectRoot, allNodes);
    }

    private int fallbackSingleModule(Output out, Path cwd) {
        var projectOpt = ProjectDetector.detect(cwd);
        if (projectOpt.isEmpty()) { out.fail("No project found."); return 3; }
        var project = projectOpt.get();

        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        var resolver   = new GraphResolver();
        var session    = resolver.newSession(localRepo);
        var result     = resolver.resolve(project.root(), session, List.of(GraphResolver.CENTRAL));

        return generateSbom(out,
                project.effectiveGroupId(), project.artifactId(), project.version(),
                project.root(), result.graph().allNodes());
    }

    private int generateSbom(Output out,
                              String groupId, String artifactId, String version,
                              Path projectRoot,
                              Map<dev.jolt.core.graph.GA, DependencyNode> allNodes) {
        String gavStr    = groupId + ":" + artifactId + ":" + version;
        UUID   uuid      = UUID.nameUUIDFromBytes(gavStr.getBytes(StandardCharsets.UTF_8));
        String rootBomRef = purl(groupId, artifactId, version);

        // Build BOM
        Bom bom = new Bom();
        bom.setSerialNumber("urn:uuid:" + uuid);
        bom.setVersion(1);

        // Metadata — no timestamp so output is byte-identical across runs
        Metadata metadata = new Metadata();
        Component metaComp = new Component();
        metaComp.setType(Component.Type.APPLICATION);
        metaComp.setGroup(groupId);
        metaComp.setName(artifactId);
        metaComp.setVersion(version);
        metaComp.setBomRef(rootBomRef);
        metaComp.setPurl(rootBomRef);
        metadata.setComponent(metaComp);
        bom.setMetadata(metadata);

        // Components — sorted by PURL for determinism
        var components = new ArrayList<Component>();
        for (var node : sortedByPurl(allNodes.values())) {
            var c = new Component();
            c.setType(Component.Type.LIBRARY);
            c.setGroup(node.coordinate().groupId());
            c.setName(node.coordinate().artifactId());
            c.setVersion(node.coordinate().version());
            String pu = purl(node.coordinate().groupId(),
                             node.coordinate().artifactId(),
                             node.coordinate().version());
            c.setBomRef(pu);
            c.setPurl(pu);
            if (node.checksumSha256() != null && !node.checksumSha256().isEmpty()) {
                c.setHashes(List.of(new Hash(Hash.Algorithm.SHA_256, node.checksumSha256())));
            }
            components.add(c);
        }
        bom.setComponents(components);

        // Dependencies section — invert parents relationship
        // children[X] = set of GAVs that X depends on directly
        var children = new LinkedHashMap<String, LinkedHashSet<String>>();
        children.put(rootBomRef, new LinkedHashSet<>());
        for (var node : allNodes.values()) {
            String nodePurl = purl(node.coordinate().groupId(),
                                   node.coordinate().artifactId(),
                                   node.coordinate().version());
            children.computeIfAbsent(nodePurl, k -> new LinkedHashSet<>());
            if (node.parents().isEmpty()) {
                // Direct dependency of root
                children.get(rootBomRef).add(nodePurl);
            }
            for (GAV parentGav : node.parents()) {
                String parentPurl = purl(parentGav.groupId(), parentGav.artifactId(),
                                         parentGav.version());
                children.computeIfAbsent(parentPurl, k -> new LinkedHashSet<>())
                        .add(nodePurl);
            }
        }

        var depsSection = new ArrayList<Dependency>();
        var sortedRefs  = new ArrayList<>(children.keySet());
        java.util.Collections.sort(sortedRefs);
        for (String ref : sortedRefs) {
            var dep = new Dependency(ref);
            var childList = new ArrayList<>(children.getOrDefault(ref, new LinkedHashSet<>()));
            java.util.Collections.sort(childList);
            for (String child : childList) dep.addDependency(new Dependency(child));
            depsSection.add(dep);
        }
        bom.setDependencies(depsSection);

        // Serialize
        boolean useXml = "xml".equalsIgnoreCase(format);
        String defaultName = useXml ? "bom.xml" : "bom.json";
        Path target = outputPath != null
                ? Path.of(outputPath).toAbsolutePath()
                : projectRoot.resolve(defaultName);

        try {
            String serialized;
            if (useXml) {
                var gen = BomGeneratorFactory.createXml(Version.VERSION_15, bom);
                serialized = stripXmlTimestamp(gen.toXmlString());
            } else {
                var gen = BomGeneratorFactory.createJson(Version.VERSION_15, bom);
                serialized = stripJsonTimestamp(gen.toJsonString());
            }
            Files.writeString(target, serialized, StandardCharsets.UTF_8);
            out.success("SBOM written → " + target);
            out.info("  serialNumber : urn:uuid:" + uuid);
            out.info("  components   : " + components.size());
            out.info("  format       : CycloneDX " + (useXml ? "XML" : "JSON") + " 1.5");
            return 0;
        } catch (GeneratorException | IOException e) {
            out.fail("SBOM generation failed: " + e.getMessage());
            if (parent.verbose) e.printStackTrace();
            return 1;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String stripJsonTimestamp(String json) {
        // Remove "timestamp" field injected by the CycloneDX library so output is byte-identical.
        // Pattern: optional leading newline+indent, key, value, optional trailing comma.
        String result = json.replaceAll("\\n\\s*\"timestamp\"\\s*:\\s*\"[^\"]+\",?", "");
        // Also handle case where timestamp is first key (metadata object starts with it).
        result = result.replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]+\",?\\s*", "");
        return result;
    }

    private static String stripXmlTimestamp(String xml) {
        return xml.replaceAll("\\s*<timestamp>[^<]*</timestamp>", "");
    }

    private static String purl(String g, String a, String v) {
        return "pkg:maven/" + g + "/" + a + "@" + v;
    }

    private static List<DependencyNode> sortedByPurl(Collection<DependencyNode> nodes) {
        var list = new ArrayList<>(nodes);
        list.sort((x, y) -> purl(x.coordinate().groupId(),
                                  x.coordinate().artifactId(),
                                  x.coordinate().version())
                .compareTo(purl(y.coordinate().groupId(),
                                y.coordinate().artifactId(),
                                y.coordinate().version())));
        return list;
    }
}
