package dev.jolt.adapter.git;

import dev.jolt.core.adapter.VcsAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Git VCS adapter. Phase 1: full implementation.
 */
public final class GitAdapter implements VcsAdapter {

    @Override
    public boolean detect(Path path) {
        Path p = path.toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve(".git"))) return true;
            p = p.getParent();
        }
        return false;
    }

    @Override
    public boolean isUserConfigured() {
        try {
            String name = run("git", "config", "user.name").trim();
            String email = run("git", "config", "user.email").trim();
            return !name.isEmpty() && !email.isEmpty();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public String currentBranch(Path repoRoot) {
        throw new UnsupportedOperationException("git currentBranch: not yet implemented (Phase 1)");
    }

    @Override
    public List<Path> changedFiles(Path repoRoot, String sinceRef) {
        throw new UnsupportedOperationException("git changedFiles: not yet implemented (Phase 3)");
    }

    @Override
    public void init(Path path) {
        throw new UnsupportedOperationException("git init: not yet implemented (Phase 1)");
    }

    private String run(String... command) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
