package dev.jolt.toolchain;

import dev.jolt.core.adapter.ToolchainProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Default JDK discovery and provisioning. Phase 2: full implementation.
 */
public final class DefaultToolchainProvider implements ToolchainProvider {

    @Override
    public Optional<Path> findJdk(int majorVersion) {
        throw new UnsupportedOperationException("findJdk: not yet implemented (Phase 2)");
    }

    @Override
    public List<JdkInfo> listJdks() {
        throw new UnsupportedOperationException("listJdks: not yet implemented (Phase 2)");
    }

    @Override
    public void installJdk(int majorVersion, String distribution) {
        throw new UnsupportedOperationException("installJdk: not yet implemented (Phase 2)");
    }

    @Override
    public JdkInfo activeJdk() {
        throw new UnsupportedOperationException("activeJdk: not yet implemented (Phase 2)");
    }
}
