package dev.jolt.adapter.docker;

import dev.jolt.core.adapter.ContainerAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Docker container adapter. Phase 1: full implementation.
 */
public final class DockerAdapter implements ContainerAdapter {

    @Override
    public boolean detect() {
        try {
            return new ProcessBuilder("docker", "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public boolean isDaemonReachable() {
        try {
            return new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public void generateDockerfile(Path projectRoot) {
        throw new UnsupportedOperationException("docker init: not yet implemented (Phase 1)");
    }

    @Override
    public void generateCompose(Path projectRoot) {
        throw new UnsupportedOperationException("docker compose: not yet implemented (Phase 1)");
    }
}
