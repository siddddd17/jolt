# Jolt — Build-From-Scratch Specification

> **You are building `jolt`: an opinionated developer-tooling CLI for Java.** This document is a
> complete, self-contained build plan. Read all of §1–§2 before writing any code — the
> architectural decisions there are what separate a real tool from a shell-script wrapper. Then
> build phase by phase (§6). Each phase has acceptance criteria that **must pass** before you
> move to the next. Do not declare a phase done until its criteria pass in CI.

> **How to work through this:** Treat each phase as a milestone. Commit per logical unit. Write
> tests alongside code, not after. When a decision is marked NON-NEGOTIABLE, implement it as
> written — if you believe a different approach is better, leave a `// NOTE:` and proceed as
> specified. The binary name `jolt` appears in exactly one constant (`Jolt.NAME`) so it can be
> renamed later in one place.

> **Name context:** an existing Java *library* called "Jolt" (JSON transformation) exists. It is
> a library, not a CLI, so the binary name does not technically collide, but keep branding
> distinct. Do not depend on or reference that library.

---

## 1. What Jolt Is

A single, consistent CLI on top of Maven, Gradle, Docker, Git, and the JDK that eliminates
repetitive Java setup, configuration, and execution. The analogy:

```
Rust    → cargo
Python  → uv
Node    → npm / pnpm
Java    → jolt
```

**Jolt does not replace Maven or Gradle.** It makes common Java workflows fast, discoverable,
standardized, and — the part most wrappers miss — **reproducible**.

### The thing that makes this matter

A naive build of this tool just runs `mvn`/`gradle` and parses their text output. That is a
glorified Makefile and provides no durable value. Jolt's value comes from **owning the
dependency-resolution engine and a reproducibility guarantee** (a real lockfile). That single
decision (§2.1) is what unlocks lockfiles, supply-chain scanning, and build-graph analysis, and
it is the reason a senior engineer would adopt this over their own shell aliases.

---

## 2. Core Architectural Decisions (NON-NEGOTIABLE)

### 2.1 Embed Maven Resolver — never scrape `mvn` text output

All dependency resolution uses the **Maven Resolver (Aether)** libraries embedded directly, not
`mvn dependency:tree` parsing.

Dependencies (pin exact versions; resolve latest stable at build time):
- `org.apache.maven.resolver:maven-resolver-api`
- `org.apache.maven.resolver:maven-resolver-impl`
- `org.apache.maven.resolver:maven-resolver-connector-basic`
- `org.apache.maven.resolver:maven-resolver-transport-http`
- `org.apache.maven.resolver:maven-resolver-supplier` (ready-made `RepositorySystem`)
- `org.apache.maven:maven-model-builder` (effective POM: parent inheritance, BOM import, property interpolation)
- `org.apache.maven:maven-settings-builder` (read `~/.m2/settings.xml`: mirrors, proxies, auth)

This produces the resolved dependency graph as **structured data**: exact versions, scopes,
classifiers, the resolving repository, conflict sets, and per-artifact checksums. Everything
downstream depends on this.

For Gradle projects, use the **Gradle Tooling API** to extract the resolved graph — never parse
console output.

### 2.2 Lockfile-first reproducibility (`jolt.lock`)

Maven resolution is **not** reproducible across machines (version ranges, SNAPSHOTs,
`dependencyManagement`/BOM ordering, per-machine `settings.xml` mirrors). Jolt fixes this.

- `jolt.lock` pins the **fully-resolved** graph: every `group:artifact:version`, scope,
  classifier/type, resolving repository, and SHA-256 of each artifact JAR **and** its POM.
- The lockfile is **derived**, never hand-edited. `pom.xml` / `build.gradle` stays the single
  source of truth for *declared* dependencies. **Do not introduce a competing manifest** (no
  `jolt.toml` for dependencies) — it would break IDE integration and fragment the ecosystem.
- Version ranges resolve at lock-update time and freeze to exact versions.
- SNAPSHOTs are not reproducible: pin to the resolved timestamped snapshot
  (e.g. `1.0-20240101.123456-1`) and warn; refuse under `--strict`.

Two modes (mirrors `npm install` vs `npm ci`, `cargo` vs `cargo --locked`):
- **update** (default when pom changed): resolve fresh, write/refresh `jolt.lock`.
- **frozen** (`--locked` / `--frozen`, default in CI): resolve strictly from `jolt.lock`; **fail
  with exit code 4** if `pom.xml` and `jolt.lock` disagree or any checksum mismatches.

### 2.3 Adapter architecture

Commands depend only on interfaces; adapters hide tool specifics.

```
BuildSystemAdapter   // MavenAdapter now; GradleAdapter; BazelAdapter later
ContainerAdapter     // DockerAdapter; PodmanAdapter later
VcsAdapter           // GitAdapter
ToolchainProvider    // JDK discovery/provisioning
```

Adapter selection is by project detection (§2.5). No command may reference `mvn`, `gradle`,
`docker`, or `git` directly.

### 2.4 Resolved-graph data model

One internal model that adapters populate and all commands consume.

```
DependencyNode {
  coordinate:        GAV            // group, artifact, version
  requestedVersions: List<String>  // before mediation
  selectedVersion:   String        // after mediation
  scope:             Scope          // compile|provided|runtime|test|system|import
  classifier:        String?
  type:              String         // jar, pom, ...
  optional:          boolean
  exclusions:        List<GA>
  repository:        String         // resolving repo id/url
  checksumSha256:    String
  pomChecksumSha256: String
  parents:           List<GAV>      // who pulled this in (for `deps why`)
}

DependencyGraph {
  roots:     List<DependencyNode>
  allNodes:  Map<GA, DependencyNode>
  conflicts: List<Conflict>         // GA requested at >1 version
}
```

### 2.5 Workspace / multi-module awareness (first-class, not a v2 afterthought)

`run`, `test`, `info`, and `deps` must work in a multi-module reactor from day one.

- **Detection:** walk up from CWD to the reactor root (Maven aggregator `<modules>`, or
  `settings.gradle(.kts)` `include`). Build the **module graph** in topological order.
- **Selection:** module-scoped commands take a selector: `jolt run :payments-api`,
  `jolt test --module billing`. When ambiguous, list modules and ask.
- **Projection:** scope to the selected module **plus its dependencies** (Maven `-pl <m> -am`).
  Never rebuild the whole reactor unnecessarily.
- **Single root lockfile:** one `jolt.lock` at the reactor root capturing the union resolved
  graph, with per-module resolution recorded (cargo-workspace model). Not one per module.
- **Affected-module analysis** (the differentiator): intersect the module graph with `git diff`
  to compute changed-or-downstream modules for `jolt test --affected`.

### 2.6 Implementation language & distribution

Build `jolt` in **Java 21**, ship as a **GraalVM native image** for instant startup.

Rationale (hold this line if asked to use Go/Rust): Jolt must embed Maven Resolver, which is a
Java library. Building in Java means reusing the *exact* resolution engine instead of
reimplementing it; Go/Rust ports pay a large tax re-deriving dependency resolution. Java +
native-image also dogfoods GraalVM.

- CLI framework: **Picocli** (first-class GraalVM native-image support, subcommands, completion).
- Build Jolt with Maven (dogfood).
- Provide a JVM-jar fallback for platforms without a native image.
- All output flows through an `Output` abstraction supporting `--quiet`, `--verbose`, `--json`,
  `--no-color`, and the `NO_COLOR` env var.

---

## 3. Repository Layout

Root package: `dev.jolt`.

```
jolt/
├── pom.xml                       # multi-module aggregator
├── jolt-cli/                     # Picocli entrypoint, command classes, Output
│   └── dev.jolt.cli
├── jolt-core/                    # domain model, orchestration
│   ├── dev.jolt.core.graph       # DependencyGraph, DependencyNode, Conflict
│   ├── dev.jolt.core.lock        # jolt.lock read/write/verify/diff
│   ├── dev.jolt.core.workspace   # reactor detection, module graph, affected-set
│   └── dev.jolt.core.project     # project detection & metadata
├── jolt-adapter-maven/           # Maven Resolver + model-builder + settings-builder
├── jolt-adapter-gradle/          # Gradle Tooling API
├── jolt-adapter-docker/          # Dockerfile/compose generation + build
├── jolt-adapter-git/             # status, diff, user-config checks
├── jolt-toolchain/               # JDK discovery / provisioning
├── jolt-templates/               # scaffolding templates (resources)
└── jolt-it/                      # end-to-end integration tests + fixtures
```

---

## 4. Command Specifications

Global contract for every command:
- Global flags: `--quiet`, `--verbose`, `--json`, `--no-color`, `--cwd <dir>`, `--locked`.
- Exit codes: `0` success; `1` expected failure (test fail / validation fail); `2` usage error;
  `3` environment not ready; `4` lock or checksum mismatch.
- `--json` emits machine-readable output; default is human-readable with ✓/✗/⚠ markers.

### 4.1 `jolt doctor` — environment validation (project-aware)
- **Java:** installed; print version; `JAVA_HOME` set. If a project declares a required JDK
  (Maven toolchains / `maven.compiler.release` / `.java-version` / Gradle toolchain), compare to
  the active JDK and flag mismatch with a remediation hint (`jolt jdk use 21`).
- **Build tools:** Maven and/or Gradle present, with versions.
- **Git:** installed; `user.name` and `user.email` set.
- **Docker:** installed; daemon reachable.
- **Network:** can reach configured Maven repositories (respect `settings.xml` mirrors).
- Output per-check status + summary; exit 3 if any hard check fails. `--json` for CI.

### 4.2 `jolt new <template> <name>` — scaffolding
Templates: `maven`, `library`, `spring`.
- `maven`: `pom.xml`, `README.md`, `.gitignore`, `src/main/java`, `src/test/java`, a `Main`
  class, a sample JUnit 5 test. Inits git.
- `library`: as above + publishing config (sources/javadoc jars, license,
  `distributionManagement` placeholder).
- `spring`: working Spring Boot app with layered packages
  (`controller/ service/ repository/ entity/ dto/ config/ exception/`), an actuator health
  endpoint, a smoke test. Resolve current stable Spring Boot version at scaffold time; never
  hardcode.

Templates are resource files with token substitution (`{{groupId}}`, `{{artifactId}}`,
`{{javaVersion}}`). After scaffolding, run an initial resolve and write `jolt.lock`.

### 4.3 `jolt run [target] [-- args...]` — execute
- No target: find `public static void main(String[])` classes under the relevant module's
  `src/main/java`. One → run it. Many → list and ask. Spring Boot → prefer
  `@SpringBootApplication`.
- Target: match by simple name or FQN.
- Multi-module: accept a module selector; build only that module `+ -am`.
- Pass-through: args after `--` go to the program. Support `--jvm-args`, `--profile <p>`
  (Spring), env passthrough.
- Build only if sources are newer than outputs.

### 4.4 `jolt test [target]` — testing
- `jolt test` → all tests (module-scoped if a module is selected).
- `jolt test ClassName` → `-Dtest=ClassName`.
- `jolt test ClassName#method` → `-Dtest=ClassName#method`.
- `jolt test --affected [--since <ref>]` → compute affected modules (§2.5) and test only those.
- Clean pass/fail summary; exit 1 on failure. `--json` for per-test results.

### 4.5 `jolt deps` — dependency management
- `deps search <term>`: query Maven Central (+ configured repos); show latest version, full
  coordinates, description; brief cache.
- `deps add <coords> [--scope] [--no-resolve]`: insert into `pom.xml` **format-preservingly**
  (surgical edits; preserve comments/whitespace). If a BOM/`dependencyManagement` already governs
  the version, **omit the version**. Re-resolve, rewrite `jolt.lock`, and **report any conflict
  or downgrade** the addition introduces.
- `deps remove <coords>`: remove + re-resolve/re-lock.
- `deps list [--tree]`: declared deps; `--tree` renders the resolved graph from the model.
- `deps why <coords>`: print the path explaining why a transitive dep is present.
- `deps outdated`: resolved versions vs latest available.
- `deps audit`: scan resolved graph against OSV/OWASP; report CVEs by severity; exit 1 over a
  configurable threshold. *(Phase 4.)*

### 4.6 `jolt docker` — containerization
- `docker init`: multi-stage, layered Dockerfile on `eclipse-temurin:21-jre`, non-root user,
  layered application jars, plus a `.dockerignore`.
- `docker compose`: `docker-compose.yml` for the app.
- `docker spring-postgres`: Dockerfile + compose with Postgres service, named volume,
  healthchecks wired to the actuator endpoint.
- *(Phase 4)* `docker init --jlink`: custom minimal runtime via `jlink`/`jdeps` on distroless,
  with embedded SBOM.

### 4.7 `jolt ci` — pipeline generation
- `ci github`: `.github/workflows/build.yml` — checkout, set up matching JDK, cache deps, build,
  test with `--locked`, package.
- `ci gitlab`: equivalent `.gitlab-ci.yml`.
- Generated pipelines run Jolt in **frozen/locked** mode so CI verifies reproducibility.

### 4.8 `jolt info` — inspection
Aggregate report: project type, Java version, build tool, dependency count, test count, Docker
present?, CI present?, **module tree** (multi-module), and lockfile status (in-sync / drifted).
`--json` for tooling.

### 4.9 `jolt clean` — cleanup
Delegate `clean` to the build tool, plus optional cleanup of `logs/`, `tmp/`, generated files.
`--all` also clears Jolt's local caches.

### 4.10 `jolt jdk` — toolchain management *(Phase 2)*
- `jdk list`: JDKs Jolt can see.
- `jdk install <version> [--distribution temurin|graalvm|...]`: provision a JDK.
- `jdk use <version>`: pin the project's JDK (project marker; integrates with `doctor`).

---

## 5. `jolt.lock` Format

Human-readable, derived, committed to VCS. TOML-style (cargo/uv aesthetic); a JSON variant is
acceptable if simpler.

```toml
# jolt.lock — generated, do not edit by hand
version = 1
generated_with = "jolt 0.1.0"

[meta]
root = "."
build_system = "maven"

[[package]]
coordinate   = "org.springframework.boot:spring-boot-starter-web:3.4.0"
scope        = "compile"
repository   = "https://repo.maven.apache.org/maven2"
sha256       = "…"
pom_sha256   = "…"
requested_by = ["payments-api"]
parents      = []

[[package]]
coordinate   = "org.yaml:snakeyaml:2.2"
scope        = "compile"
repository   = "https://repo.maven.apache.org/maven2"
sha256       = "…"
pom_sha256   = "…"
requested_by = ["payments-api"]
parents      = ["org.springframework.boot:spring-boot-starter-web:3.4.0"]
```

Implement: write, read, **verify** (pom vs lock + checksums), and a human-readable **diff** for
`deps` operations.

---

## 6. Phased Roadmap

Build in order. A phase is done only when its acceptance criteria pass in CI.

### Phase 0 — Foundations & working scaffolding
Stand up the multi-module project, the Picocli skeleton, and a green native-image build. Concrete
starting artifacts are in §7 — use them verbatim as the seed, then extend.
- Aggregator `pom.xml` + all submodules from §3.
- Picocli entrypoint with all subcommands registered as stubs that print "not yet implemented".
- `Output` abstraction; global flags parsed.
- Adapter interfaces (§2.3) with a `MavenAdapter` stub.
- GraalVM native-image build wired in CI; `jolt --version` runs as a native binary.
- **Acceptance:** `jolt --help` lists every subcommand; `jolt --version` works as a native
  binary on Linux + macOS; the test harness runs and is green.

### Phase 1 — Core ergonomics (delegating allowed)
- `doctor` (project-aware), `new` (all 3 templates), `run`, `test`, `info`, `clean`.
- May delegate execution to `mvn`, but must use the project-detection model, not hardcoded paths.
- **Acceptance:** the §8 demo flow works end-to-end on a single-module project on a clean machine.

### Phase 2 — The engine (the moat)
- Embed Maven Resolver + model-builder + settings-builder (§2.1).
- Build the resolved-graph model (§2.4).
- `jolt.lock` write/read/verify/diff (§2.2, §5), update vs frozen modes.
- `deps list --tree`, `deps why`, `deps add`/`remove` (format-preserving, BOM-aware), `outdated`.
- `jolt jdk` toolchain management; `doctor` consumes it.
- **Acceptance:** `deps add` yields a correct pom + refreshed lock; `--locked` fails (exit 4) on a
  tampered lock or drifted pom; resolved graph matches `mvn dependency:tree` on fixtures; the same
  `jolt.lock` produces byte-identical resolution on two OSes in CI.

### Phase 3 — Workspace / multi-module
- Reactor detection, module graph, module selectors for `run`/`test`/`info`.
- Single root lockfile across modules.
- `test --affected` via git-diff intersection.
- **Acceptance:** on a 3+ module fixture, `run`/`test` scope correctly with `-am`; `--affected`
  builds only changed-or-downstream modules; `info` prints the module tree.

### Phase 4 — Platform / supply chain (the staff-impressive layer)
- `deps audit` (OSV/OWASP scan, severity gating).
- SBOM generation (CycloneDX) for project and container images.
- `docker init --jlink` (custom runtime, distroless, embedded SBOM, multi-arch).
- `jolt native` (GraalVM native-image workflow for the *user's* project).
- `ci` templates run audit + frozen builds.
- **Acceptance:** `audit` flags a known-vulnerable fixture dep and exits non-zero; the CycloneDX
  SBOM validates against schema; `jolt native` produces a runnable binary for a sample app.

### Phase 5 — Polish & adoption
- Gradle adapter (Tooling API) at parity for core commands.
- Shell completions; `jolt migrate` (OpenRewrite wrapper for JDK/Spring upgrades).
- Internal artifact-repo support (Nexus/Artifactory auth, air-gapped mirrors).

---

## 7. Phase 0 Concrete Scaffolding (seed code)

Use these as the literal starting point. Resolve current stable versions for any property
marked `LATEST_STABLE` at build time.

### 7.1 Aggregator `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>dev.jolt</groupId>
  <artifactId>jolt-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <picocli.version>LATEST_STABLE</picocli.version>
    <maven.resolver.version>LATEST_STABLE</maven.resolver.version>
    <maven.version>LATEST_STABLE</maven.version>
    <junit.version>LATEST_STABLE</junit.version>
  </properties>

  <modules>
    <module>jolt-core</module>
    <module>jolt-adapter-maven</module>
    <module>jolt-adapter-gradle</module>
    <module>jolt-adapter-docker</module>
    <module>jolt-adapter-git</module>
    <module>jolt-toolchain</module>
    <module>jolt-templates</module>
    <module>jolt-cli</module>
    <module>jolt-it</module>
  </modules>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>info.picocli</groupId>
        <artifactId>picocli</artifactId>
        <version>${picocli.version}</version>
      </dependency>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${junit.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

### 7.2 CLI entrypoint (`jolt-cli/.../cli/Jolt.java`)

```java
package dev.jolt.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = Jolt.NAME,
    mixinStandardHelpOptions = true,
    versionProvider = JoltVersionProvider.class,
    description = "Opinionated developer tooling for Java.",
    subcommands = {
        DoctorCommand.class, NewCommand.class, RunCommand.class, TestCommand.class,
        DepsCommand.class, DockerCommand.class, CiCommand.class, InfoCommand.class,
        CleanCommand.class, JdkCommand.class
    })
public final class Jolt implements Runnable {

    /** The one place the binary name lives. Rename here only. */
    public static final String NAME = "jolt";

    @Option(names = "--quiet", scope = CommandLine.ScopeType.INHERIT) boolean quiet;
    @Option(names = "--verbose", scope = CommandLine.ScopeType.INHERIT) boolean verbose;
    @Option(names = "--json", scope = CommandLine.ScopeType.INHERIT) boolean json;
    @Option(names = "--no-color", scope = CommandLine.ScopeType.INHERIT) boolean noColor;

    @Override public void run() {
        // No subcommand -> show usage.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Jolt())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
        System.exit(code);
    }
}
```

### 7.3 A stub subcommand (pattern for all of §4)

```java
package dev.jolt.cli;

import picocli.CommandLine.Command;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Validate the environment for Java development.")
final class DoctorCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.out.println("doctor: not yet implemented");
        return 0; // replace with real exit semantics in Phase 1
    }
}
```

Create equivalent stubs for `new`, `run`, `test`, `deps`, `docker`, `ci`, `info`, `clean`, `jdk`.

### 7.4 Adapter interface seed (`jolt-core/.../adapter/BuildSystemAdapter.java`)

```java
package dev.jolt.core.adapter;

import dev.jolt.core.graph.DependencyGraph;
import java.nio.file.Path;

public interface BuildSystemAdapter {
    boolean detect(Path projectRoot);          // is this my kind of project?
    DependencyGraph resolve(Path projectRoot);  // structured graph, NOT parsed text
    int test(Path projectRoot, TestRequest req);
    int run(Path projectRoot, RunRequest req);
    int clean(Path projectRoot);
}
```

### 7.5 Native-image build
Add GraalVM `native-image` configuration (via the `native-maven-plugin`) to `jolt-cli`. Use
Picocli's `picocli-codegen` annotation processor to emit reflection/resource config so the native
binary works without manual hints. CI must produce a `jolt` binary on Linux and macOS.

---

## 8. MVP Success Criteria (the demo that must pass)

On a fresh machine, with no manual Maven/Docker/Spring-Initializr/GitHub-Actions steps:

```
jolt doctor
jolt new spring inventory-service
cd inventory-service
jolt run
jolt test
jolt deps add org.projectlombok:lombok
jolt docker init
jolt ci github
jolt info
```

…all succeed, `jolt.lock` is generated and committed, and a **second machine reproduces the exact
resolved dependency set** from the lockfile. If that holds, the MVP is real.

---

## 9. Testing Strategy
- **Unit tests:** graph model, lock read/write/verify, format-preserving pom editing,
  affected-set logic.
- **Fixtures** in `jolt-it/fixtures/`: single-module Maven, multi-module reactor, Spring Boot, a
  BOM-using project, and one with a known-vulnerable dependency.
- **Cross-machine reproducibility test** (CI matrix, ≥2 OSes): resolve twice, assert identical
  `jolt.lock`.
- **Golden-file tests** for scaffolding output and generated Dockerfiles/CI yaml.
- Every command has ≥1 end-to-end test running the real binary against a fixture.

---

## 10. Non-Goals (MVP)
No code generation, ORM/OpenAPI/Kubernetes generation, AI integrations, interview/leetcode
tooling, or cloud deployment. These are future plugins behind the adapter boundaries — not MVP
scope. Resisting them is a feature.

---

## 11. Definition of Done
- All §4 commands implemented for Maven; Gradle adapter at least stubbed.
- `jolt.lock` reproducibility proven in CI across two OSes.
- `deps audit` + CycloneDX SBOM functional (Phase 4).
- Native-image binary published for Linux + macOS; JVM-jar fallback available.
- The §8 demo passes in CI on a clean image.
- Binary name isolated to `Jolt.NAME` (rename-ready).
