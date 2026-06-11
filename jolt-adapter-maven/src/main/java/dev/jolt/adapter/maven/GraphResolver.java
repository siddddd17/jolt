package dev.jolt.adapter.maven;

import dev.jolt.core.graph.*;
import org.apache.maven.model.building.*;
import org.apache.maven.repository.internal.*;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.*;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.ArtifactDescriptorReader;
import org.eclipse.aether.impl.VersionRangeResolver;
import org.eclipse.aether.impl.VersionResolver;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Resolves the full transitive dependency graph for a Maven project using the
 * embedded Aether resolver (no mvn process, no text-output parsing).
 *
 * <p>Architecture:
 * <ol>
 *   <li>Read the root pom.xml via {@code maven-model-builder} to produce an effective
 *       model (parent inheritance, BOM imports, property interpolation).</li>
 *   <li>Feed direct deps + managed deps from the effective model into an Aether
 *       {@link CollectRequest}.</li>
 *   <li>Aether resolves transitive deps by downloading each dependency's POM via
 *       {@link DefaultArtifactDescriptorReader}, which also uses the model builder,
 *       so BOM imports in transitive POMs are resolved correctly.</li>
 *   <li>Walk the resolved tree to build a {@link DependencyGraph}.</li>
 * </ol>
 */
public final class GraphResolver {

    /** Canonical Maven Central URL (matches what mvn dependency:tree reports). */
    public static final RemoteRepository CENTRAL = new RemoteRepository.Builder(
            "central", "default", "https://repo1.maven.org/maven2").build();

    private final RepositorySystem system;
    private final ModelBuilder modelBuilder;

    public GraphResolver() {
        this.modelBuilder = new DefaultModelBuilderFactory().newInstance();
        this.system = newRepositorySystem(this.modelBuilder);
    }

    @SuppressWarnings("deprecation")
    private static RepositorySystem newRepositorySystem(ModelBuilder modelBuilder) {
        // DefaultServiceLocator is deprecated in favour of the Guice/Sisu DI container
        // but it is the only Plexus-free bootstrap option in the Maven 3.9 / Resolver 1.9
        // package family. We acknowledge the deprecation; native-image hardening is deferred.
        var locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.setServices(ModelBuilder.class, modelBuilder);
        locator.addService(ArtifactDescriptorReader.class, DefaultArtifactDescriptorReader.class);
        locator.addService(VersionResolver.class, DefaultVersionResolver.class);
        locator.addService(VersionRangeResolver.class, DefaultVersionRangeResolver.class);
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    /** Create a session, auto-loading {@code ~/.m2/settings.xml} for mirrors/proxies/auth. */
    public DefaultRepositorySystemSession newSession(Path localRepoPath) {
        return newSession(localRepoPath, SettingsLoader.loadUserSettings());
    }

    /** Create a session with an explicitly supplied {@link Settings} — used in tests. */
    DefaultRepositorySystemSession newSession(Path localRepoPath, Settings settings) {
        var session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepoPath.toFile())));
        session.setSystemProperties(System.getProperties());
        SettingsLoader.applyToSession(session, settings);
        return session;
    }

    /** Resolves the project at {@code projectRoot} and returns both graph and Aether tree. */
    public ResolveResult resolve(Path projectRoot,
                                 DefaultRepositorySystemSession session,
                                 List<RemoteRepository> repos) {
        Path pomFile = projectRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            throw new IllegalArgumentException("No pom.xml found in " + projectRoot);
        }

        // ── Step 1: effective POM for the root project ──────────────────────────
        var req = new DefaultModelBuildingRequest()
                .setPomFile(pomFile.toFile())
                .setSystemProperties(System.getProperties())
                .setUserProperties(new Properties())
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                .setProcessPlugins(false)
                .setModelResolver(new AetherModelResolver(system, session, repos));

        org.apache.maven.model.Model effectiveModel;
        try {
            effectiveModel = modelBuilder.build(req).getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new RuntimeException("Failed to compute effective POM for " + pomFile, e);
        }

        // ── Step 2: build the CollectRequest ────────────────────────────────────
        var collect = new CollectRequest();
        for (var dep : effectiveModel.getDependencies()) {
            if (dep.getVersion() != null && !dep.getVersion().isBlank()) {
                collect.addDependency(toAetherDep(dep));
            }
        }
        // Managed deps (from BOM imports) supply version management for transitive deps.
        // IMPORTANT: pass null scope for entries without an explicit non-compile scope
        // declaration.  ClassicDependencyManager only overrides scope when the managed
        // entry has a non-null scope; passing "compile" (the Maven default) incorrectly
        // overrides scope inheritance for deps pulled in through test/provided parents.
        if (effectiveModel.getDependencyManagement() != null) {
            for (var managed : effectiveModel.getDependencyManagement().getDependencies()) {
                if ("import".equals(managed.getScope()) || managed.getVersion() == null) continue;
                collect.addManagedDependency(toAetherManagedDep(managed));
            }
        }
        collect.setRepositories(repos);

        // ── Step 3: collect non-verbose tree (clean structure for display) ───────
        DependencyNode aetherRoot;
        try {
            aetherRoot = system.collectDependencies(session, collect).getRoot();
        } catch (DependencyCollectionException e) {
            throw new RuntimeException("Dependency collection failed for " + projectRoot, e);
        }

        // ── Step 4: collect verbose tree for scope computation ───────────────────
        // All POM metadata is already cached from step 3; this second collection is
        // a fast local read.  We need the verbose tree because Aether's conflict
        // resolution places each winner at its SHORTEST path, which may be under a
        // test-scoped parent even when the artifact is also reachable via a compile
        // path.  The verbose tree retains LOSER stubs at those compile-path positions,
        // letting us observe the widest effective scope across all paths.
        var widestScopes = new LinkedHashMap<GA, String>();
        try {
            Path localRepoPath = session.getLocalRepositoryManager()
                    .getRepository().getBasedir().toPath();
            var verboseSession = newSession(localRepoPath);
            verboseSession.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, Boolean.TRUE);
            DependencyNode verboseRoot = system.collectDependencies(verboseSession, collect).getRoot();
            // Phase 1: walk verbose tree; LOSER stubs tell us compile-path occurrences
            computeWidestScopes(verboseRoot, null, widestScopes);
        } catch (DependencyCollectionException ignored) {
            // Verbose pass failed — fall through; Phase 2 alone will still produce
            // correct results for non-conflict deps.
        }
        // Phase 2: walk the clean tree propagating widestScopes as parent scopes so
        // that children of a conflict-winner (e.g. spring-jcl under spring-core) also
        // inherit the widened scope rather than the test-path-derived one.
        propagateWidestScopes(aetherRoot, null, widestScopes);

        // ── Step 5: resolve (download) artifacts ────────────────────────────────
        try {
            system.resolveDependencies(session, new DependencyRequest(aetherRoot, null));
        } catch (DependencyResolutionException e) {
            // Partial resolution is acceptable — POM-only artifacts have no JAR.
        }

        // ── Step 6: walk clean tree → DependencyGraph ───────────────────────────
        var allNodes = new LinkedHashMap<GA, dev.jolt.core.graph.DependencyNode>();
        var conflicts = new ArrayList<Conflict>();
        walkChildren(aetherRoot, null, allNodes, conflicts, session, repos, widestScopes);

        var roots = allNodes.values().stream()
                .filter(n -> n.parents().isEmpty())
                .toList();

        return new ResolveResult(new DependencyGraph(roots, allNodes, conflicts),
                aetherRoot, widestScopes);
    }

    /**
     * Phase 1 — walk the verbose Aether tree recording the widest effective scope
     * for each GA.  Recurses into WINNER nodes only; LOSER stubs are leaf entries
     * (their sub-tree is the same as the winner's and would be double-counted).
     */
    private static void computeWidestScopes(DependencyNode node, String parentEffective,
            Map<GA, String> widestScopes) {
        for (var child : node.getChildren()) {
            var dep = child.getDependency();
            var art = child.getArtifact();
            if (dep == null || art == null) continue;
            boolean isLoser = child.getData().get(ConflictResolver.NODE_DATA_WINNER) != null;
            String raw = dep.getScope() != null ? dep.getScope() : "compile";
            String effective = deriveScope(parentEffective, raw);
            var ga = new GA(art.getGroupId(), art.getArtifactId());
            widestScopes.merge(ga, effective,
                    (a, b) -> scopeWidth(a) >= scopeWidth(b) ? a : b);
            if (!isLoser) {
                computeWidestScopes(child, effective, widestScopes);
            }
        }
    }

    /**
     * Phase 2 — walk the clean (non-verbose) Aether tree.  For each node, derive
     * its effective scope using the WIDEST scope of its parent (from {@code
     * widestScopes}) rather than the raw tree-path scope.  This propagates the
     * compile scope of a conflict-winner down to its children even when the winner
     * node is positioned under a test-scoped branch.
     */
    private static void propagateWidestScopes(DependencyNode node, String parentWidestScope,
            Map<GA, String> widestScopes) {
        for (var child : node.getChildren()) {
            var dep = child.getDependency();
            var art = child.getArtifact();
            if (dep == null || art == null) continue;
            String raw = dep.getScope() != null ? dep.getScope() : "compile";
            String derived = deriveScope(parentWidestScope, raw);
            var ga = new GA(art.getGroupId(), art.getArtifactId());
            // Only widen, never narrow.
            String current = widestScopes.getOrDefault(ga, derived);
            String newScope = scopeWidth(derived) > scopeWidth(current) ? derived : current;
            widestScopes.put(ga, newScope);
            // Recurse using this GA's widest scope as the parent scope for its children.
            propagateWidestScopes(child, newScope, widestScopes);
        }
    }

    private void walkChildren(
            DependencyNode node,
            GAV immediateParent,
            Map<GA, dev.jolt.core.graph.DependencyNode> allNodes,
            List<Conflict> conflicts,
            DefaultRepositorySystemSession session,
            List<RemoteRepository> repos,
            Map<GA, String> widestScopes) {

        for (var child : node.getChildren()) {
            var dep = child.getDependency();
            if (dep == null) continue;

            var art = child.getArtifact();
            var ga = new GA(art.getGroupId(), art.getArtifactId());
            var version = art.getVersion();
            var gav = new GAV(art.getGroupId(), art.getArtifactId(), version);

            // Use the precomputed widest effective scope; fall back to the raw POM
            // scope (correct for direct deps that are not in the verbose tree map).
            String effectiveScope = widestScopes.getOrDefault(ga,
                    dep.getScope() != null ? dep.getScope() : "compile");

            String sha256 = null, pomSha256 = null;
            if (art.getFile() != null && art.getFile().exists()) {
                sha256 = sha256Hex(art.getFile().toPath());
            }
            try {
                var pomArt = new DefaultArtifact(
                        art.getGroupId(), art.getArtifactId(), "", "pom", version);
                var pomResult = system.resolveArtifact(session,
                        new ArtifactRequest(pomArt, repos, null));
                if (pomResult.getArtifact().getFile() != null) {
                    pomSha256 = sha256Hex(pomResult.getArtifact().getFile().toPath());
                }
            } catch (ArtifactResolutionException ignored) {}

            String repoUrl = child.getRepositories().isEmpty()
                    ? CENTRAL.getUrl()
                    : child.getRepositories().get(0).getUrl();

            List<GAV> parents = immediateParent != null
                    ? List.of(immediateParent)
                    : List.of();

            if (!allNodes.containsKey(ga)) {
                allNodes.put(ga, new dev.jolt.core.graph.DependencyNode(
                        gav, List.of(version), version,
                        Scope.of(effectiveScope),
                        art.getClassifier().isEmpty() ? null : art.getClassifier(),
                        art.getExtension().isEmpty() ? "jar" : art.getExtension(),
                        dep.isOptional(),
                        List.of(),
                        repoUrl, sha256, pomSha256, parents));
            } else {
                // Diamond dep: add this parent to the existing node's parent list.
                var existing = allNodes.get(ga);
                if (immediateParent != null && !existing.parents().contains(immediateParent)) {
                    var merged = new ArrayList<>(existing.parents());
                    merged.add(immediateParent);
                    allNodes.put(ga, new dev.jolt.core.graph.DependencyNode(
                            existing.coordinate(), existing.requestedVersions(),
                            existing.selectedVersion(), existing.scope(),
                            existing.classifier(), existing.type(), existing.optional(),
                            existing.exclusions(), existing.repository(),
                            existing.checksumSha256(), existing.pomChecksumSha256(), merged));
                }
            }

            walkChildren(child, gav, allNodes, conflicts, session, repos, widestScopes);
        }
    }

    /**
     * Maven scope inheritance table.
     * <p>
     * When a dependency is pulled in transitively through a parent of a given scope,
     * the child's effective scope is the narrower of the two.
     */
    public static String deriveScope(String parentEffectiveScope, String childDeclaredScope) {
        if (childDeclaredScope == null || childDeclaredScope.isBlank()) childDeclaredScope = "compile";
        if (parentEffectiveScope == null || "compile".equals(parentEffectiveScope)) {
            return childDeclaredScope;
        }
        return switch (parentEffectiveScope) {
            case "test"     -> "test";     // everything under a test parent becomes test
            case "runtime"  -> "compile".equals(childDeclaredScope) ? "runtime" : childDeclaredScope;
            case "provided" -> "provided"; // everything under a provided parent becomes provided
            default         -> childDeclaredScope;
        };
    }

    /** Width ordering: compile > provided > runtime > test > system. */
    static int scopeWidth(String scope) {
        return switch (scope == null ? "compile" : scope) {
            case "compile"  -> 5;
            case "provided" -> 4;
            case "runtime"  -> 3;
            case "test"     -> 2;
            case "system"   -> 1;
            default         -> 5;
        };
    }

    static String sha256Hex(Path file) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(file));
            var b = md.digest();
            var sb = new StringBuilder(64);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    /** Converts a Maven model dep to an Aether dep for use as a DIRECT dependency. */
    private static org.eclipse.aether.graph.Dependency toAetherDep(
            org.apache.maven.model.Dependency d) {
        var scope = (d.getScope() != null && !d.getScope().isBlank()) ? d.getScope() : "compile";
        var art = new DefaultArtifact(
                d.getGroupId(), d.getArtifactId(),
                d.getClassifier() != null ? d.getClassifier() : "",
                d.getType() != null ? d.getType() : "jar",
                d.getVersion());
        return new org.eclipse.aether.graph.Dependency(art, scope, d.isOptional());
    }

    /**
     * Converts a Maven model dep to an Aether dep for MANAGED dependency registration.
     *
     * <p>Crucially, scope is set to {@code null} when the BOM does not explicitly
     * declare one. {@code ClassicDependencyManager} only overrides scope when the
     * managed entry has a non-null scope; passing "compile" (the Maven-default) would
     * incorrectly force compile scope on deps that should inherit a narrower scope
     * (e.g. compile deps of a test-scoped library would wrongly become compile).
     */
    private static org.eclipse.aether.graph.Dependency toAetherManagedDep(
            org.apache.maven.model.Dependency d) {
        String declaredScope = d.getScope();
        // Treat null and the default "compile" as "not explicitly set" → pass null.
        String managedScope = (declaredScope != null && !declaredScope.isBlank()
                && !"compile".equals(declaredScope)) ? declaredScope : null;
        var art = new DefaultArtifact(
                d.getGroupId(), d.getArtifactId(), "",
                d.getType() != null ? d.getType() : "jar",
                d.getVersion());
        return new org.eclipse.aether.graph.Dependency(art, managedScope, false);
    }

    /** Result of a full resolution: structured graph + raw Aether tree for display. */
    public record ResolveResult(
            DependencyGraph graph,
            DependencyNode aetherRoot,
            Map<GA, String> widestScopes) {}
}
