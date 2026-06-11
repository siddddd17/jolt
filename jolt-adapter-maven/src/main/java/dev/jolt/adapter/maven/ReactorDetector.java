package dev.jolt.adapter.maven;

import dev.jolt.core.graph.DependencyGraph;
import dev.jolt.core.graph.GA;
import dev.jolt.core.graph.GAV;
import dev.jolt.core.project.ReactorGraph;
import dev.jolt.core.project.ReactorModule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Detects and scans Maven multi-module reactors.
 *
 * <p>This is a lightweight scanner: it reads POM files with DOM (no Aether) to build the
 * inter-module dependency graph. External artifact resolution is deferred; each
 * {@link ReactorModule#externalGraph()} is {@link DependencyGraph#empty()}.
 */
public final class ReactorDetector {

    private ReactorDetector() {}

    /**
     * Walk upward from {@code cwd} and return the highest ancestor directory that contains
     * a {@code pom.xml} with a {@code <modules>} block.
     *
     * <p>Walking stops at the first directory that has no {@code pom.xml}, which breaks the
     * parent-POM chain. Falls back to the nearest {@code pom.xml} directory for single-module
     * projects.
     */
    public static Optional<Path> findRoot(Path cwd) {
        Path candidate        = cwd.toAbsolutePath().normalize();
        Path nearestPom       = null;
        Path highestMultiMod  = null;

        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.exists(pom)) {
                if (nearestPom == null) nearestPom = candidate;
                if (hasModules(pom))   highestMultiMod = candidate;
            } else if (nearestPom != null) {
                // First gap in the pom.xml chain — stop.
                break;
            }
            candidate = candidate.getParent();
        }

        if (highestMultiMod != null) return Optional.of(highestMultiMod);
        return Optional.ofNullable(nearestPom);
    }

    /**
     * Scan the reactor rooted at {@code root} and return a {@link ReactorGraph} with
     * topologically-ordered modules and reverse-dependency edges populated.
     * Each module's {@link ReactorModule#externalGraph()} is empty.
     */
    public static ReactorGraph scan(Path root) {
        PomInfo reactorInfo = parsePom(root.resolve("pom.xml"));
        List<String> moduleDirs = reactorInfo.modulePaths();

        if (moduleDirs.isEmpty()) {
            // Single-module project
            var single = new ReactorModule(
                    reactorInfo.gav(), Path.of("."), Set.of(), DependencyGraph.empty());
            return new ReactorGraph(root, List.of(single), Map.of());
        }

        // ── Pass 1: collect module coordinates ──────────────────────────────────
        var infoByPath = new LinkedHashMap<Path, PomInfo>();   // relPath → pom info
        for (String modDir : moduleDirs) {
            Path relPath = Path.of(modDir);
            Path pomFile = root.resolve(modDir).resolve("pom.xml");
            if (Files.exists(pomFile)) {
                infoByPath.put(relPath, parsePom(pomFile));
            }
        }

        // GA → canonical GAV for all known modules (enables version-agnostic matching)
        var gaToGav = new HashMap<GA, GAV>();
        for (var info : infoByPath.values()) {
            if (info.gav() != null) gaToGav.put(info.gav().ga(), info.gav());
        }

        // ── Pass 2: build ReactorModule list ─────────────────────────────────────
        // Skip entries where POM parsing failed (null GAV).
        var modules = new ArrayList<ReactorModule>();
        for (var entry : infoByPath.entrySet()) {
            if (entry.getValue().gav() == null) continue;
            Path    relPath = entry.getKey();
            PomInfo info    = entry.getValue();

            var moduleDeps = new LinkedHashSet<GAV>();
            for (GA depGA : info.depGAs()) {
                GAV sibling = gaToGav.get(depGA);
                if (sibling != null) moduleDeps.add(sibling);
            }
            modules.add(new ReactorModule(
                    info.gav(), relPath, Set.copyOf(moduleDeps), DependencyGraph.empty()));
        }

        // ── Build reverse-dependency map ─────────────────────────────────────────
        var reverseDeps = new LinkedHashMap<GAV, Set<GAV>>();
        for (var m : modules) {
            for (GAV dep : m.moduleDependencies()) {
                reverseDeps.computeIfAbsent(dep, k -> new LinkedHashSet<>())
                           .add(m.coordinates());
            }
        }

        // ── Topological sort (Kahn's) ─────────────────────────────────────────────
        List<ReactorModule> sorted = topoSort(modules, reverseDeps);

        // Freeze
        var frozenReverse = new LinkedHashMap<GAV, Set<GAV>>();
        reverseDeps.forEach((k, v) -> frozenReverse.put(k, Set.copyOf(v)));

        return new ReactorGraph(root, List.copyOf(sorted), Map.copyOf(frozenReverse));
    }

    // ── Topological sort ─────────────────────────────────────────────────────────

    private static List<ReactorModule> topoSort(
            List<ReactorModule> modules, Map<GAV, Set<GAV>> reverseDeps) {

        var gavToModule = new LinkedHashMap<GAV, ReactorModule>();
        var inDegree    = new HashMap<GAV, Integer>();
        for (var m : modules) {
            gavToModule.put(m.coordinates(), m);
            inDegree.put(m.coordinates(), m.moduleDependencies().size());
        }

        // Seed: modules with no inter-module dependencies (sorted for determinism)
        var ready = new TreeSet<>(Comparator.comparing(GAV::toString));
        inDegree.forEach((gav, deg) -> { if (deg == 0) ready.add(gav); });

        var result = new ArrayList<ReactorModule>();
        while (!ready.isEmpty()) {
            GAV gav = ready.first();
            ready.remove(gav);
            result.add(gavToModule.get(gav));
            for (GAV dependent : reverseDeps.getOrDefault(gav, Set.of())) {
                int newDeg = inDegree.merge(dependent, -1, Integer::sum);
                if (newDeg == 0) ready.add(dependent);
            }
        }

        // Append any stragglers (cycle guard — should not occur in valid Maven reactor)
        for (var m : modules) {
            if (!result.contains(m)) result.add(m);
        }
        return result;
    }

    // ── POM parsing ──────────────────────────────────────────────────────────────

    private record PomInfo(GAV gav, List<GA> depGAs, List<String> modulePaths) {}

    private static PomInfo parsePom(Path pomFile) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            var doc = dbf.newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();
            var root = doc.getDocumentElement();

            String groupId    = directChildText(root, "groupId");
            String artifactId = directChildText(root, "artifactId");
            String version    = directChildText(root, "version");

            // Inherit groupId / version from <parent> if not declared at project level
            var parents = root.getElementsByTagName("parent");
            if (parents.getLength() > 0) {
                var parentElem = (Element) parents.item(0);
                if (groupId == null) groupId = directChildText(parentElem, "groupId");
                if (version  == null) version  = directChildText(parentElem, "version");
            }

            GAV gav = (groupId != null && artifactId != null && version != null)
                    ? new GAV(groupId, artifactId, version) : null;

            // Collect direct <dependency> GAs (skip <dependencyManagement>)
            var depGAs = new ArrayList<GA>();
            var depElems = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depElems.getLength(); i++) {
                var dep = (Element) depElems.item(i);
                if (isInsideTag(dep, "dependencyManagement")) continue;
                if (isInsideTag(dep, "plugin"))               continue;
                String dg = directChildText(dep, "groupId");
                String da = directChildText(dep, "artifactId");
                if (dg != null && da != null) depGAs.add(new GA(dg, da));
            }

            // Collect <module> entries
            var modulePaths = new ArrayList<String>();
            var moduleElems = root.getElementsByTagName("module");
            for (int i = 0; i < moduleElems.getLength(); i++) {
                String text = moduleElems.item(i).getTextContent().trim();
                if (!text.isEmpty()) modulePaths.add(text);
            }

            return new PomInfo(gav, List.copyOf(depGAs), List.copyOf(modulePaths));
        } catch (Exception e) {
            return new PomInfo(null, List.of(), List.of());
        }
    }

    private static boolean hasModules(Path pomFile) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            var doc = dbf.newDocumentBuilder().parse(pomFile.toFile());
            return doc.getElementsByTagName("module").getLength() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInsideTag(Node node, String tagName) {
        Node parent = node.getParentNode();
        while (parent != null) {
            if (tagName.equals(parent.getNodeName())) return true;
            parent = parent.getParentNode();
        }
        return false;
    }

    private static String directChildText(Element parent, String tagName) {
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
}
