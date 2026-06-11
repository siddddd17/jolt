package dev.jolt.core.adapter;

import java.nio.file.Path;

public interface ContainerAdapter {

    /** Returns true if the container runtime is installed and detectable. */
    boolean detect();

    /** Returns true if the container daemon/runtime is reachable. */
    boolean isDaemonReachable();

    /** Generates a production-ready Dockerfile at the project root. */
    void generateDockerfile(Path projectRoot);

    /** Generates a docker-compose.yml at the project root. */
    void generateCompose(Path projectRoot);
}
