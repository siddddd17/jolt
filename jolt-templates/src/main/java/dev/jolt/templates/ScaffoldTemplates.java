package dev.jolt.templates;

/** Immutable scaffold templates. Tokens: {{groupId}} {{artifactId}} {{version}} {{package}} {{className}} {{springBootVersion}} {{javaVersion}}. */
public final class ScaffoldTemplates {

    private ScaffoldTemplates() {}

    // -------------------------------------------------------------------------
    // Spring Boot template
    // -------------------------------------------------------------------------

    public static final String SPRING_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>

              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>{{springBootVersion}}</version>
                <relativePath/>
              </parent>

              <groupId>{{groupId}}</groupId>
              <artifactId>{{artifactId}}</artifactId>
              <version>0.1.0-SNAPSHOT</version>
              <name>{{artifactId}}</name>
              <description>Spring Boot application scaffolded by jolt.</description>

              <properties>
                <java.version>{{javaVersion}}</java.version>
              </properties>

              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-actuator</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-test</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration>
                      <mainClass>{{package}}.{{className}}</mainClass>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    public static final String SPRING_APP = """
            package {{package}};

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class {{className}} {

                public static void main(String[] args) {
                    SpringApplication.run({{className}}.class, args);
                }
            }
            """;

    public static final String SPRING_CONTROLLER = """
            package {{package}}.controller;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            import java.util.List;
            import java.util.Map;

            @RestController
            @RequestMapping("/api")
            public class {{controllerName}} {

                @GetMapping("/items")
                public List<Map<String, Object>> listItems() {
                    return List.of(Map.of("id", 1, "name", "Sample Item", "available", true));
                }
            }
            """;

    public static final String SPRING_APP_TEST = """
            package {{package}};

            import org.junit.jupiter.api.Test;
            import org.springframework.boot.test.context.SpringBootTest;

            @SpringBootTest
            class {{className}}Tests {

                @Test
                void contextLoads() {}
            }
            """;

    public static final String SPRING_GITIGNORE = """
            # Build
            target/
            !.mvn/wrapper/maven-wrapper.jar
            !**/src/main/**/target/
            !**/src/test/**/target/

            # IDE
            .idea/
            *.iml
            .eclipse/
            .settings/
            .classpath
            .project
            *.swp
            *.swo

            # OS
            .DS_Store
            Thumbs.db

            # Jolt
            jolt.lock
            """;

    // -------------------------------------------------------------------------
    // Maven (plain) template
    // -------------------------------------------------------------------------

    public static final String MAVEN_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>

              <groupId>{{groupId}}</groupId>
              <artifactId>{{artifactId}}</artifactId>
              <version>1.0-SNAPSHOT</version>
              <name>{{artifactId}}</name>

              <properties>
                <maven.compiler.release>{{javaVersion}}</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>

              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.11.4</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    public static final String MAVEN_MAIN = """
            package {{package}};

            public class Main {

                public static void main(String[] args) {
                    System.out.println("Hello from {{artifactId}}!");
                }
            }
            """;

    public static final String MAVEN_TEST = """
            package {{package}};

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            class MainTest {

                @Test
                void sanity() {
                    assertTrue(true, "sanity check");
                }
            }
            """;

    public static final String MAVEN_GITIGNORE = """
            target/

            .idea/
            *.iml
            .eclipse/
            .settings/
            .classpath
            .project

            .DS_Store
            Thumbs.db

            jolt.lock
            """;

    // -------------------------------------------------------------------------
    // Library template (extends Maven)
    // -------------------------------------------------------------------------

    public static final String LIBRARY_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>

              <groupId>{{groupId}}</groupId>
              <artifactId>{{artifactId}}</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <packaging>jar</packaging>
              <name>{{artifactId}}</name>
              <description>{{artifactId}} library</description>

              <properties>
                <maven.compiler.release>{{javaVersion}}</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>

              <licenses>
                <license>
                  <name>Apache License, Version 2.0</name>
                  <url>https://www.apache.org/licenses/LICENSE-2.0</url>
                </license>
              </licenses>

              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.11.4</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-source-plugin</artifactId>
                    <version>3.3.1</version>
                    <executions>
                      <execution>
                        <id>attach-sources</id>
                        <goals><goal>jar-no-fork</goal></goals>
                      </execution>
                    </executions>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.10.1</version>
                    <executions>
                      <execution>
                        <id>attach-javadocs</id>
                        <goals><goal>jar</goal></goals>
                      </execution>
                    </executions>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                  </plugin>
                </plugins>
              </build>

              <distributionManagement>
                <!-- TODO: configure your repository here -->
                <repository>
                  <id>your-repo</id>
                  <url>https://your.repo/releases</url>
                </repository>
              </distributionManagement>
            </project>
            """;

    // -------------------------------------------------------------------------
    // Docker templates
    // -------------------------------------------------------------------------

    public static final String DOCKERFILE_SPRING = """
            # Build stage
            FROM eclipse-temurin:21-jdk AS builder
            WORKDIR /workspace
            COPY pom.xml .
            COPY src src
            RUN apt-get update && apt-get install -y maven && \\
                mvn package -DskipTests --batch-mode

            # Runtime stage
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            VOLUME /tmp

            ARG JAR_FILE=/workspace/target/*.jar
            COPY --from=builder ${JAR_FILE} app.jar

            RUN useradd -r -u 1001 -g root jolt-app && \\
                chown jolt-app:root app.jar
            USER jolt-app

            EXPOSE 8080
            ENTRYPOINT ["java", "-jar", "app.jar"]
            """;

    public static final String DOCKERFILE_MAVEN = """
            # Build stage
            FROM eclipse-temurin:21-jdk AS builder
            WORKDIR /workspace
            COPY pom.xml .
            COPY src src
            RUN apt-get update && apt-get install -y maven && \\
                mvn package -DskipTests --batch-mode

            # Runtime stage
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            VOLUME /tmp

            ARG JAR_FILE=/workspace/target/*.jar
            COPY --from=builder ${JAR_FILE} app.jar

            RUN useradd -r -u 1001 -g root jolt-app && \\
                chown jolt-app:root app.jar
            USER jolt-app

            ENTRYPOINT ["java", "-jar", "app.jar"]
            """;

    public static final String DOCKERIGNORE = """
            target/
            .git/
            .gitignore
            *.md
            .idea/
            *.iml
            jolt.lock
            """;

    public static final String DOCKERFILE_JLINK = """
            # Stage 1: Build
            FROM eclipse-temurin:21-jdk AS builder
            WORKDIR /workspace
            COPY pom.xml .
            COPY src src
            RUN apt-get update -q && apt-get install -y -q --no-install-recommends maven \\
                && mvn package -DskipTests --batch-mode \\
                && rm -rf /var/lib/apt/lists/*

            # Stage 2: Compute minimal module set and build custom JRE via jlink
            FROM eclipse-temurin:21-jdk AS jlink
            COPY --from=builder /workspace/target/*.jar /app/app.jar
            RUN MODULES=$(jdeps \\
                  --ignore-missing-deps \\
                  --print-module-deps \\
                  --multi-release 21 \\
                  /app/app.jar 2>/dev/null || echo "java.base") && \\
                MODULES="${MODULES:-java.base}" && \\
                jlink \\
                  --add-modules "${MODULES},java.logging,java.naming,java.security.sasl,jdk.unsupported" \\
                  --strip-debug \\
                  --no-man-pages \\
                  --no-header-files \\
                  --compress=zip-6 \\
                  --output /customjre

            # Stage 3: Distroless final image with custom JRE
            FROM gcr.io/distroless/java-base-debian12:nonroot
            WORKDIR /app
            COPY --from=jlink /customjre /customjre
            COPY --from=builder /workspace/target/*.jar app.jar
            LABEL org.opencontainers.image.documentation="bom.json"
            ENTRYPOINT ["/customjre/bin/java", "-jar", "app.jar"]
            """;

    // -------------------------------------------------------------------------
    // CI templates
    // -------------------------------------------------------------------------

    public static final String GITHUB_WORKFLOW = """
            name: CI

            on:
              push:
                branches: [ "main" ]
              pull_request:
                branches: [ "main" ]

            jobs:
              build:
                runs-on: ubuntu-latest

                steps:
                  - uses: actions/checkout@v4

                  - name: Set up JDK {{javaVersion}}
                    uses: actions/setup-java@v4
                    with:
                      java-version: '{{javaVersion}}'
                      distribution: temurin
                      cache: maven

                  - name: Build and test
                    run: mvn verify --batch-mode

                  - name: Package
                    run: mvn package -DskipTests --batch-mode

                  - name: Upload artifact
                    uses: actions/upload-artifact@v4
                    with:
                      name: jar
                      path: target/*.jar
            """;

    public static final String GITLAB_CI = """
            image: eclipse-temurin:21-jdk

            variables:
              MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"

            cache:
              paths:
                - .m2/repository

            stages:
              - build
              - test
              - package

            build:
              stage: build
              script:
                - apt-get update && apt-get install -y maven
                - mvn compile --batch-mode

            test:
              stage: test
              script:
                - apt-get update && apt-get install -y maven
                - mvn test --batch-mode

            package:
              stage: package
              script:
                - apt-get update && apt-get install -y maven
                - mvn package -DskipTests --batch-mode
              artifacts:
                paths:
                  - target/*.jar
            """;

    // -------------------------------------------------------------------------
    // Docker Compose templates
    // -------------------------------------------------------------------------

    public static final String DOCKER_COMPOSE = """
            version: '3.8'

            services:
              app:
                build: .
                ports:
                  - "{{port}}:{{port}}"
                environment:
                  - SPRING_PROFILES_ACTIVE=docker
            """;

    public static final String DOCKER_COMPOSE_SPRING_POSTGRES = """
            version: '3.8'

            services:
              app:
                build: .
                ports:
                  - "{{port}}:{{port}}"
                environment:
                  - SPRING_PROFILES_ACTIVE=docker
                  - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/{{artifactId}}
                  - SPRING_DATASOURCE_USERNAME=postgres
                  - SPRING_DATASOURCE_PASSWORD=postgres
                depends_on:
                  db:
                    condition: service_healthy

              db:
                image: postgres:16-alpine
                environment:
                  POSTGRES_DB: {{artifactId}}
                  POSTGRES_USER: postgres
                  POSTGRES_PASSWORD: postgres
                ports:
                  - "5432:5432"
                volumes:
                  - postgres_data:/var/lib/postgresql/data
                healthcheck:
                  test: ["CMD-SHELL", "pg_isready -U postgres"]
                  interval: 10s
                  timeout: 5s
                  retries: 5

            volumes:
              postgres_data:
            """;

    public static final String DOCKERFILE_SPRING_POSTGRES = """
            # Build stage
            FROM eclipse-temurin:21-jdk AS builder
            WORKDIR /workspace
            COPY pom.xml .
            COPY src src
            RUN apt-get update && apt-get install -y maven && \\
                mvn package -DskipTests --batch-mode

            # Runtime stage
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            VOLUME /tmp

            ARG JAR_FILE=/workspace/target/*.jar
            COPY --from=builder ${JAR_FILE} app.jar

            RUN useradd -r -u 1001 -g root jolt-app && \\
                chown jolt-app:root app.jar
            USER jolt-app

            EXPOSE {{port}}
            ENTRYPOINT ["java", "-jar", "app.jar"]
            """;
}
