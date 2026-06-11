package dev.jolt.core.lock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Parses the TOML-style {@code jolt.lock} file written by {@link LockfileWriter}. */
public final class LockfileReader {

    private LockfileReader() {}

    public static Optional<Lockfile> readIfPresent(Path lockFile) {
        if (!Files.exists(lockFile)) return Optional.empty();
        try {
            return Optional.of(parse(Files.readString(lockFile, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + lockFile, e);
        }
    }

    static Lockfile parse(String content) {
        int version = Lockfile.CURRENT_VERSION;
        String generatedWith = "unknown";
        Instant generatedAt = Instant.EPOCH;
        String root = ".";
        String buildSystem = "maven";
        var packages = new ArrayList<LockEntry>();

        boolean inPackage = false;
        String coord = null, scope = "compile", repo = "", sha = "", pomSha = "", module = "";
        List<String> requestedBy = List.of(), parents = List.of();

        for (String raw : content.lines().toList()) {
            String line = raw.strip();

            if (line.startsWith("#") || line.isEmpty()) continue;

            if (line.equals("[[package]]")) {
                if (inPackage && coord != null) {
                    packages.add(new LockEntry(coord, scope, repo, sha, pomSha,
                            requestedBy, parents, module));
                }
                inPackage = true;
                coord = null; scope = "compile"; repo = ""; sha = ""; pomSha = ""; module = "";
                requestedBy = List.of(); parents = List.of();
                continue;
            }

            // [[module]] section header: flush current package, exit package mode.
            // The name/path fields that follow are not relevant to LockEntry parsing.
            if (line.equals("[[module]]")) {
                if (inPackage && coord != null) {
                    packages.add(new LockEntry(coord, scope, repo, sha, pomSha,
                            requestedBy, parents, module));
                }
                inPackage = false;
                coord = null;
                continue;
            }

            // A non-array section header (e.g. [meta]) ends the package context.
            if (line.startsWith("[") && !line.startsWith("[[")) {
                if (inPackage && coord != null) {
                    packages.add(new LockEntry(coord, scope, repo, sha, pomSha,
                            requestedBy, parents, module));
                    inPackage = false; coord = null;
                }
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String val = line.substring(eq + 1).strip();

            if (inPackage) {
                switch (key) {
                    case "coordinate"   -> coord  = unquote(val);
                    case "scope"        -> scope  = unquote(val);
                    case "repository"   -> repo   = unquote(val);
                    case "sha256"       -> sha    = unquote(val);
                    case "pom_sha256"   -> pomSha = unquote(val);
                    case "module"       -> module = unquote(val);
                    case "requested_by" -> requestedBy = parseStringArray(val);
                    case "parents"      -> parents     = parseStringArray(val);
                }
            } else {
                switch (key) {
                    case "version" -> {
                        try { version = Integer.parseInt(unquote(val)); }
                        catch (NumberFormatException ignored) {}
                    }
                    case "generated_with" -> generatedWith = unquote(val);
                    case "generated_at"   -> {
                        try { generatedAt = Instant.parse(unquote(val)); }
                        catch (Exception ignored) {}
                    }
                    case "root"          -> root        = unquote(val);
                    case "build_system"  -> buildSystem = unquote(val);
                }
            }
        }
        // Flush last block
        if (inPackage && coord != null) {
            packages.add(new LockEntry(coord, scope, repo, sha, pomSha, requestedBy, parents, module));
        }

        return new Lockfile(version, generatedWith, generatedAt, root, buildSystem, packages);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static List<String> parseStringArray(String s) {
        s = s.strip();
        if (!s.startsWith("[") || !s.endsWith("]")) return List.of();
        s = s.substring(1, s.length() - 1).strip();
        if (s.isEmpty()) return List.of();
        var result = new ArrayList<String>();
        for (String part : s.split(",")) {
            result.add(unquote(part.strip()));
        }
        return List.copyOf(result);
    }
}
