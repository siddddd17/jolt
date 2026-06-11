package dev.jolt.core.graph;

import java.util.List;

public final class DependencyNode {

    private final GAV coordinate;
    private final List<String> requestedVersions;
    private final String selectedVersion;
    private final Scope scope;
    private final String classifier;
    private final String type;
    private final boolean optional;
    private final List<GA> exclusions;
    private final String repository;
    private final String checksumSha256;
    private final String pomChecksumSha256;
    private final List<GAV> parents;

    public DependencyNode(
            GAV coordinate,
            List<String> requestedVersions,
            String selectedVersion,
            Scope scope,
            String classifier,
            String type,
            boolean optional,
            List<GA> exclusions,
            String repository,
            String checksumSha256,
            String pomChecksumSha256,
            List<GAV> parents) {
        this.coordinate = coordinate;
        this.requestedVersions = List.copyOf(requestedVersions);
        this.selectedVersion = selectedVersion;
        this.scope = scope;
        this.classifier = classifier;
        this.type = type != null ? type : "jar";
        this.optional = optional;
        this.exclusions = List.copyOf(exclusions);
        this.repository = repository;
        this.checksumSha256 = checksumSha256;
        this.pomChecksumSha256 = pomChecksumSha256;
        this.parents = List.copyOf(parents);
    }

    public GAV coordinate() { return coordinate; }
    public List<String> requestedVersions() { return requestedVersions; }
    public String selectedVersion() { return selectedVersion; }
    public Scope scope() { return scope; }
    public String classifier() { return classifier; }
    public String type() { return type; }
    public boolean optional() { return optional; }
    public List<GA> exclusions() { return exclusions; }
    public String repository() { return repository; }
    public String checksumSha256() { return checksumSha256; }
    public String pomChecksumSha256() { return pomChecksumSha256; }
    public List<GAV> parents() { return parents; }

    @Override
    public String toString() {
        return coordinate + " [" + scope + "]";
    }
}
