package dev.jolt.adapter.maven;

import dev.jolt.core.adapter.BuildSystemAdapter;
import dev.jolt.core.adapter.RunRequest;
import dev.jolt.core.adapter.TestRequest;
import dev.jolt.core.graph.DependencyGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class MavenAdapter implements BuildSystemAdapter {

    @Override
    public boolean detect(Path projectRoot) {
        return Files.exists(projectRoot.resolve("pom.xml"));
    }

    @Override
    public DependencyGraph resolve(Path projectRoot) {
        Path localRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        var resolver = new GraphResolver();
        var session = resolver.newSession(localRepo);
        return resolver.resolve(projectRoot, session, List.of(GraphResolver.CENTRAL)).graph();
    }

    @Override
    public int test(Path projectRoot, TestRequest req) {
        throw new UnsupportedOperationException("test: delegated via ProcessRunner in CLI layer");
    }

    @Override
    public int run(Path projectRoot, RunRequest req) {
        throw new UnsupportedOperationException("run: delegated via ProcessRunner in CLI layer");
    }

    @Override
    public int clean(Path projectRoot) {
        throw new UnsupportedOperationException("clean: delegated via ProcessRunner in CLI layer");
    }
}
