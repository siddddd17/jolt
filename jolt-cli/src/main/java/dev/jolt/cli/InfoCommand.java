package dev.jolt.cli;

import dev.jolt.adapter.maven.ReactorDetector;
import dev.jolt.core.project.ProjectDetector;
import dev.jolt.core.project.ReactorGraph;
import dev.jolt.core.project.ReactorModule;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "info",
         description = "Print aggregate project report: type, versions, dep count, module tree, lockfile status.")
final class InfoCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Override
    public Integer call() {
        var out = new Output(parent.quiet, parent.verbose, parent.json, parent.noColor);
        Path cwd = parent.effectiveCwd();

        // Detect project (single- or multi-module)
        var rootOpt = ReactorDetector.findRoot(cwd);
        if (rootOpt.isEmpty()) {
            // Fall back to simple ProjectDetector
            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) {
                out.fail("No project found. Run from a directory containing pom.xml or build.gradle.");
                return 3;
            }
            var p = projectOpt.get();
            printSingleModule(out, p.root(), p.artifactId(), p.version(),
                    p.buildSystem().displayName(), p.effectiveGroupId(),
                    p.isSpringBoot(), p.isMultiModule());
            return 0;
        }

        Path reactorRoot  = rootOpt.get();
        ReactorGraph graph = ReactorDetector.scan(reactorRoot);

        boolean multiModule    = graph.modules().size() > 1;
        boolean dockerPresent  = Files.exists(reactorRoot.resolve("Dockerfile"));
        boolean ciPresent      = Files.exists(reactorRoot.resolve(".github/workflows"))
                                 || Files.exists(reactorRoot.resolve(".gitlab-ci.yml"));
        boolean lockPresent    = Files.exists(reactorRoot.resolve("jolt.lock"));
        int sourceCount        = ProjectDetector.countSourceFiles(reactorRoot);
        int testCount          = ProjectDetector.countTestClasses(reactorRoot);

        // Derive display name from the first module's coordinates (reactor root)
        var firstModule = graph.modules().isEmpty() ? null : graph.modules().get(0);
        String artifactId = firstModule != null ? firstModule.coordinates().artifactId() : reactorRoot.getFileName().toString();
        String version    = firstModule != null ? firstModule.coordinates().version() : "unknown";
        String groupId    = firstModule != null ? firstModule.coordinates().groupId() : "unknown";

        // For a proper reactor the root pom isn't listed as a module — detect via ProjectDetector
        var projectOpt = ProjectDetector.detect(reactorRoot);
        if (projectOpt.isPresent()) {
            var p = projectOpt.get();
            artifactId = p.artifactId();
            version    = p.version() != null ? p.version() : version;
            groupId    = p.effectiveGroupId();
        }

        if (parent.json) {
            out.info("{");
            out.info("  \"artifactId\": \"" + artifactId + "\",");
            out.info("  \"version\": \"" + version + "\",");
            out.info("  \"buildSystem\": \"Maven\",");
            out.info("  \"multiModule\": " + multiModule + ",");
            out.info("  \"modules\": " + graph.modules().size() + ",");
            out.info("  \"sourceFiles\": " + sourceCount + ",");
            out.info("  \"testFiles\": " + testCount + ",");
            out.info("  \"docker\": " + dockerPresent + ",");
            out.info("  \"ci\": " + ciPresent + ",");
            out.info("  \"lockfile\": " + lockPresent);
            out.info("}");
        } else {
            out.info(artifactId + (version != null ? " " + version : ""));
            out.info("  Build system  : Maven");
            out.info("  Group         : " + groupId);
            if (multiModule) {
                out.info("  Multi-module  : yes");
                out.info("  Modules:");
                for (var m : graph.modules()) {
                    String label = m.coordinates().artifactId();
                    if (!m.moduleDependencies().isEmpty()) {
                        String deps = m.moduleDependencies().stream()
                                .map(gav -> gav.artifactId())
                                .sorted()
                                .collect(Collectors.joining(", "));
                        label += " \u2192 " + deps;
                    }
                    out.info("    " + label);
                }
            }
            out.info("  Source files  : " + sourceCount);
            out.info("  Test files    : " + testCount);
            out.info("  Docker        : " + (dockerPresent ? "Dockerfile present" : "not configured"));
            out.info("  CI            : " + (ciPresent ? "configured" : "not configured"));
            out.info("  Lock          : " + (lockPresent ? "present" : "not generated"));
        }
        return 0;
    }

    private void printSingleModule(Output out, Path root, String artifactId, String version,
            String buildSystem, String groupId, boolean springBoot, boolean multiModule) {
        boolean dockerPresent = Files.exists(root.resolve("Dockerfile"));
        boolean ciPresent     = Files.exists(root.resolve(".github/workflows"))
                                || Files.exists(root.resolve(".gitlab-ci.yml"));
        boolean lockPresent   = Files.exists(root.resolve("jolt.lock"));
        int sourceCount       = ProjectDetector.countSourceFiles(root);
        int testCount         = ProjectDetector.countTestClasses(root);

        if (parent.json) {
            out.info("{");
            out.info("  \"artifactId\": \"" + artifactId + "\",");
            out.info("  \"version\": \"" + version + "\",");
            out.info("  \"buildSystem\": \"" + buildSystem + "\",");
            out.info("  \"springBoot\": " + springBoot + ",");
            out.info("  \"multiModule\": " + multiModule + ",");
            out.info("  \"sourceFiles\": " + sourceCount + ",");
            out.info("  \"testFiles\": " + testCount + ",");
            out.info("  \"docker\": " + dockerPresent + ",");
            out.info("  \"ci\": " + ciPresent + ",");
            out.info("  \"lockfile\": " + lockPresent);
            out.info("}");
        } else {
            String title = artifactId + (version != null ? " " + version : "");
            out.info(title);
            out.info("  Build system  : " + buildSystem);
            out.info("  Group         : " + groupId);
            if (springBoot)   out.info("  Type          : Spring Boot");
            if (multiModule)  out.info("  Multi-module  : yes");
            out.info("  Source files  : " + sourceCount);
            out.info("  Test files    : " + testCount);
            out.info("  Docker        : " + (dockerPresent ? "Dockerfile present" : "not configured"));
            out.info("  CI            : " + (ciPresent ? "configured" : "not configured"));
            out.info("  Lock          : " + (lockPresent ? "present" : "not generated"));
        }
    }
}
