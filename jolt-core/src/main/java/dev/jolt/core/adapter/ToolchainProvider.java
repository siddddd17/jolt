package dev.jolt.core.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ToolchainProvider {

    record JdkInfo(int majorVersion, String fullVersion, String distribution, Path home) {}

    /** Returns the JDK home for the requested major version, if available. */
    Optional<Path> findJdk(int majorVersion);

    /** Lists all JDKs that Jolt can discover. */
    List<JdkInfo> listJdks();

    /** Downloads and installs a JDK of the given major version and distribution. */
    void installJdk(int majorVersion, String distribution);

    /** Returns the currently active JDK. */
    JdkInfo activeJdk();
}
