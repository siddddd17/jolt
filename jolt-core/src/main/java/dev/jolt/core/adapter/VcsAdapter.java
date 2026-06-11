package dev.jolt.core.adapter;

import java.nio.file.Path;
import java.util.List;

public interface VcsAdapter {

    /** Returns true if a VCS repo is detected at or above the given path. */
    boolean detect(Path path);

    /** Returns true if user.name and user.email are configured. */
    boolean isUserConfigured();

    /** Returns the current branch name. */
    String currentBranch(Path repoRoot);

    /** Returns paths of files changed since the given ref (commit, branch, tag). */
    List<Path> changedFiles(Path repoRoot, String sinceRef);

    /** Initialises a new repository at the given path. */
    void init(Path path);
}
