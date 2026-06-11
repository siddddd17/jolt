package dev.jolt.cli;

import dev.jolt.core.project.ProjectDetector;
import dev.jolt.templates.ScaffoldTemplates;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(name = "docker",
         description = "Generate Dockerfiles and compose configurations.",
         subcommands = {
             DockerCommand.InitCommand.class,
             DockerCommand.ComposeCommand.class,
             DockerCommand.SpringPostgresCommand.class
         })
final class DockerCommand implements Callable<Integer> {

    @ParentCommand
    private Jolt parent;

    @Override
    public Integer call() {
        new Output(parent.quiet, parent.verbose, parent.json, parent.noColor)
            .info("Usage: jolt docker <subcommand>  (init | compose | spring-postgres)");
        return 0;
    }

    @Command(name = "init",
             description = "Generate a multi-stage layered Dockerfile and .dockerignore.")
    static final class InitCommand implements Callable<Integer> {

        @ParentCommand DockerCommand docker;

        @Option(names = "--force", description = "Overwrite existing Dockerfile.")
        boolean force;

        @Option(names = "--jlink",
                description = "Generate a jlink-optimised Dockerfile with a distroless final image.")
        boolean jlink;

        @Override
        public Integer call() {
            var out = new Output(docker.parent.quiet, docker.parent.verbose,
                                 docker.parent.json, docker.parent.noColor);
            Path cwd = docker.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) {
                out.fail("No project found.");
                return 3;
            }
            var project = projectOpt.get();
            Path root = project.root();

            Path dockerfile   = root.resolve("Dockerfile");
            Path dockerignore = root.resolve(".dockerignore");

            if (Files.exists(dockerfile) && !force) {
                out.warn("Dockerfile already exists. Use --force to overwrite.");
                return 0;
            }

            try {
                String template;
                if (jlink) {
                    template = ScaffoldTemplates.DOCKERFILE_JLINK;
                } else if (project.isSpringBoot()) {
                    template = ScaffoldTemplates.DOCKERFILE_SPRING;
                } else {
                    template = ScaffoldTemplates.DOCKERFILE_MAVEN;
                }

                Files.writeString(dockerfile, template);
                Files.writeString(dockerignore, ScaffoldTemplates.DOCKERIGNORE);

                if (jlink) {
                    out.success("Generated jlink Dockerfile (jdeps + jlink → distroless:nonroot)");
                } else {
                    out.success("Generated Dockerfile (eclipse-temurin:21-jre, non-root user)");
                }
                out.success("Generated .dockerignore");
                out.info("");
                out.info("Build:  docker build -t " + project.artifactId() + " .");
                out.info("Run:    docker run -p 8080:8080 " + project.artifactId());
            } catch (IOException e) {
                out.fail("docker init failed: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "compose", description = "Generate a docker-compose.yml for the application.")
    static final class ComposeCommand implements Callable<Integer> {
        @ParentCommand DockerCommand docker;

        @Option(names = "--force", description = "Overwrite existing docker-compose.yml.")
        boolean force;

        @Override
        public Integer call() {
            var out = new Output(docker.parent.quiet, docker.parent.verbose,
                                 docker.parent.json, docker.parent.noColor);
            Path cwd = docker.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) {
                out.fail("No project found.");
                return 3;
            }
            var project = projectOpt.get();
            Path root = project.root();

            Path composeFile = root.resolve("docker-compose.yml");
            if (Files.exists(composeFile) && !force) {
                out.warn("docker-compose.yml already exists. Use --force to overwrite.");
                return 0;
            }

            String port = readServerPort(root);

            try {
                String content = ScaffoldTemplates.DOCKER_COMPOSE
                        .replace("{{artifactId}}", project.artifactId())
                        .replace("{{port}}", port);
                Files.writeString(composeFile, content);
                out.success("Generated docker-compose.yml");
                out.info("");
                out.info("Start:  docker compose up --build");
                out.info("Stop:   docker compose down");
            } catch (IOException e) {
                out.fail("docker compose failed: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "spring-postgres",
             description = "Generate Dockerfile + compose with Postgres service.")
    static final class SpringPostgresCommand implements Callable<Integer> {
        @ParentCommand DockerCommand docker;

        @Option(names = "--force", description = "Overwrite existing Dockerfile and docker-compose.yml.")
        boolean force;

        @Override
        public Integer call() {
            var out = new Output(docker.parent.quiet, docker.parent.verbose,
                                 docker.parent.json, docker.parent.noColor);
            Path cwd = docker.parent.effectiveCwd();

            var projectOpt = ProjectDetector.detect(cwd);
            if (projectOpt.isEmpty()) {
                out.fail("No project found.");
                return 3;
            }
            var project = projectOpt.get();
            Path root = project.root();

            Path dockerfile  = root.resolve("Dockerfile");
            Path composeFile = root.resolve("docker-compose.yml");

            if ((Files.exists(dockerfile) || Files.exists(composeFile)) && !force) {
                out.warn("Dockerfile or docker-compose.yml already exists. Use --force to overwrite.");
                return 0;
            }

            String port = readServerPort(root);

            try {
                String dockerfileContent = ScaffoldTemplates.DOCKERFILE_SPRING_POSTGRES
                        .replace("{{artifactId}}", project.artifactId())
                        .replace("{{port}}", port);
                Files.writeString(dockerfile, dockerfileContent);
                out.success("Generated Dockerfile (Spring + Postgres, port " + port + ")");

                String composeContent = ScaffoldTemplates.DOCKER_COMPOSE_SPRING_POSTGRES
                        .replace("{{artifactId}}", project.artifactId())
                        .replace("{{port}}", port);
                Files.writeString(composeFile, composeContent);
                out.success("Generated docker-compose.yml (app + postgres:16-alpine)");

                out.info("");
                out.info("Start:  docker compose up --build");
                out.info("Stop:   docker compose down");
                out.info("");
                out.info("Remember to add the Spring Data JPA and PostgreSQL driver dependencies:");
                out.info("  jolt deps add org.springframework.boot:spring-boot-starter-data-jpa");
                out.info("  jolt deps add org.postgresql:postgresql");
            } catch (IOException e) {
                out.fail("docker spring-postgres failed: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    /** Reads server.port from src/main/resources/application.properties; defaults to "8080". */
    private static String readServerPort(Path root) {
        Path appProps = root.resolve("src/main/resources/application.properties");
        if (Files.exists(appProps)) {
            try {
                var props = new Properties();
                try (var reader = Files.newBufferedReader(appProps, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                String port = props.getProperty("server.port");
                if (port != null && !port.isBlank()) return port.trim();
            } catch (IOException ignored) {}
        }
        return "8080";
    }
}
