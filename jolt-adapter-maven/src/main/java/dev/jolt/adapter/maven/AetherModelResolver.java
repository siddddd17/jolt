package dev.jolt.adapter.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges maven-model-builder's ModelResolver to the Aether RepositorySystem so
 * DefaultModelBuilderFactory can fetch parent POMs and imported BOMs from Maven repos.
 */
final class AetherModelResolver implements ModelResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repos;

    AetherModelResolver(RepositorySystem system, RepositorySystemSession session,
                        List<RemoteRepository> repos) {
        this.system = system;
        this.session = session;
        this.repos = new ArrayList<>(repos);
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        try {
            var artifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
            var result = system.resolveArtifact(session,
                    new ArtifactRequest(artifact, repos, null));
            return new FileModelSource(result.getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(),
                dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        // Phase 2: Central only. Phase 5 will wire settings.xml repos here.
    }

    @Override
    public void addRepository(Repository repository, boolean replace)
            throws InvalidRepositoryException {
        addRepository(repository);
    }

    @Override
    public ModelResolver newCopy() {
        return new AetherModelResolver(system, session, repos);
    }
}
