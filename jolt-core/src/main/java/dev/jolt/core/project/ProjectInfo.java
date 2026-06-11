package dev.jolt.core.project;

import java.nio.file.Path;

public record ProjectInfo(
        Path root,
        BuildSystem buildSystem,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        boolean isSpringBoot,
        boolean hasSpringBootPlugin,
        boolean isMultiModule
) {
    public String displayName() {
        return artifactId + " " + version;
    }

    public String effectiveGroupId() {
        return groupId != null ? groupId : "unknown";
    }
}
