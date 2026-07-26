package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.entity.HealthCheckKind;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.repository.control.QitsConfig.ActionDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.BootstrapDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.ServiceDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfigParser.QitsConfigException;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@code .qits-config.yml} parse. Instantiated directly (no CDI) since
 * {@code parse} doesn't touch the injected {@code GitExecutor}.
 */
class QitsConfigParserTest {

  private final QitsConfigParser parser = new QitsConfigParser();

  @Test
  void emptyOrBlankIsEmptyConfig() {
    assertTrue(parser.parse("").isEmpty());
    assertTrue(parser.parse("   ").isEmpty());
    assertTrue(parser.parse(null).isEmpty());
  }

  @Test
  void versionOneWithNoSectionsIsEmptyButValid() {
    QitsConfig config = parser.parse("version: 1\n");
    assertTrue(config.isEmpty());
  }

  @Test
  void missingOrWrongVersionThrows() {
    assertThrows(
        QitsConfigException.class, () -> parser.parse("repository:\n  archetype: SERVICE\n"));
    assertThrows(QitsConfigException.class, () -> parser.parse("version: 2\n"));
  }

  @Test
  void malformedYamlThrows() {
    assertThrows(
        QitsConfigException.class, () -> parser.parse("version: 1\n  : : :\nfoo: [unterminated"));
  }

  @Test
  void parsesRepositorySection() {
    QitsConfig config =
        parser.parse(
            """
            version: 1
            repository:
              main-branch: develop
              archetype: SERVICE_TEMPLATE
            """);
    assertEquals("develop", config.repository().mainBranch());
    assertEquals(RepositoryArchetype.SERVICE_TEMPLATE, config.repository().archetype());
  }

  @Test
  void invalidArchetypeThrows() {
    assertThrows(
        QitsConfigException.class,
        () -> parser.parse("version: 1\nrepository:\n  archetype: NONSENSE\n"));
  }

  /**
   * A committed config must not be able to promote its repository to the project's wrapper: that
   * role is derived from the project slug and owned by the adopt seam. Left open, any repository
   * could mint a second wrapper by committing one line of YAML.
   */
  @Test
  void projectArchetypeIsReservedForTheWrapperAndRejected() {
    var error =
        assertThrows(
            QitsConfigException.class,
            () -> parser.parse("version: 1\nrepository:\n  archetype: PROJECT\n"));
    assertTrue(error.getMessage().contains("PROJECT"), error.getMessage());
  }

  /**
   * The starter config the project template seeds into every wrapper must itself parse. It is
   * commented almost end to end, so the only load-bearing lines are {@code version: 1} (absent, the
   * parser raises a warning on every wrapper ever created) and the absence of {@code archetype:
   * PROJECT}, which the rule above rejects.
   */
  @Test
  void theProjectTemplateStarterConfigParses() throws Exception {
    String starter =
        Files.readString(Path.of("target/classes/project-template/dot-qits-config.yml"));

    QitsConfig config = parser.parse(starter);

    assertEquals("main", config.repository().mainBranch());
    assertNull(config.repository().archetype(), "the wrapper's archetype is qits' to decide");
  }

  @Test
  void parsesActionsIncludingCheckAndEnvironment() {
    QitsConfig config =
        parser.parse(
            """
            version: 1
            actions:
              - name: build-project
                description: Full package build
                execute: ./mvnw package
                check: |
                  git diff --quiet HEAD
                interactive: false
                environment:
                  MAVEN_OPTS: -Xmx2g
              - name: run-unit-tests
                execute: ./mvnw test
            """);
    assertEquals(2, config.actions().size());
    ActionDecl build = config.actions().get(0);
    assertEquals("build-project", build.name());
    assertEquals("Full package build", build.description());
    assertEquals("./mvnw package", build.execute());
    assertTrue(build.check().contains("git diff --quiet HEAD"));
    assertEquals("-Xmx2g", build.environment().get("MAVEN_OPTS"));
    ActionDecl test = config.actions().get(1);
    assertEquals("run-unit-tests", test.name());
    assertNull(test.check());
    assertTrue(test.environment().isEmpty());
  }

  @Test
  void actionMissingNameThrows() {
    assertThrows(
        QitsConfigException.class, () -> parser.parse("version: 1\nactions:\n  - execute: ./go\n"));
  }

  @Test
  void parsesFullDaemonWithNestedEmbeddables() {
    QitsConfig config =
        parser.parse(
            """
            version: 1
            daemons:
              - name: dev-server
                description: Quarkus dev mode
                start: ./mvnw quarkus:dev
                ready-pattern: "Listening on"
                otel: true
                auto-start: true
                restart-policy: ON_FAILURE
                max-restarts: 3
                stop-signal: TERM
                environment:
                  QUARKUS_HTTP_HOST: 0.0.0.0
                web-view:
                  port: 4200
                  entry-path: /
                health-checks:
                  - name: Quarkus
                    kind: HTTP
                    port: 8080
                    path: /q/health
                    expect-status: 2xx,3xx
                    interval-ms: 5000
            """);
    assertEquals(1, config.services().size());
    ServiceDecl d = config.services().get(0);
    assertEquals("dev-server", d.name());
    assertEquals("./mvnw quarkus:dev", d.start());
    assertEquals("Listening on", d.readyPattern());
    assertEquals(Boolean.TRUE, d.otel());
    assertEquals(RestartPolicy.ON_FAILURE, d.restartPolicy());
    assertEquals(3, d.maxRestarts());
    assertEquals("0.0.0.0", d.environment().get("QUARKUS_HTTP_HOST"));
    assertEquals(4200, d.webView().port());
    assertEquals("/", d.webView().entryPath());
    assertEquals(HealthCheckKind.HTTP, d.healthChecks().get(0).kind());
    assertEquals(8080, d.healthChecks().get(0).port());
    assertEquals(Long.valueOf(5000), d.healthChecks().get(0).intervalMs());
  }

  @Test
  void parsesOrderedBootstrapSection() {
    QitsConfig config =
        parser.parse(
            """
            version: 1
            bootstrap:
              - name: install
                description: Build the reactor
                execute: ./mvnw install -DskipTests
                environment:
                  MAVEN_OPTS: -Xmx2g
              - name: seed
                execute: ./mvnw -pl cli quarkus:run -Dcli.args=seed
                check: test ! -f ~/.qits/data/h2/qits.mv.db
            """);
    assertEquals(2, config.bootstrap().size());
    BootstrapDecl install = config.bootstrap().get(0);
    assertEquals("install", install.name());
    assertEquals("Build the reactor", install.description());
    assertEquals("./mvnw install -DskipTests", install.execute());
    assertNull(install.check());
    assertEquals("-Xmx2g", install.environment().get("MAVEN_OPTS"));
    BootstrapDecl seed = config.bootstrap().get(1);
    assertEquals("seed", seed.name());
    assertEquals("test ! -f ~/.qits/data/h2/qits.mv.db", seed.check());
    assertTrue(seed.environment().isEmpty());
  }

  @Test
  void bootstrapEntryMissingNameThrows() {
    assertThrows(
        QitsConfigException.class,
        () -> parser.parse("version: 1\nbootstrap:\n  - execute: ./go\n"));
  }

  @Test
  void unknownEnumInDaemonThrows() {
    assertThrows(
        QitsConfigException.class,
        () ->
            parser.parse(
                "version: 1\ndaemons:\n  - name: d\n    start: go\n    restart-policy: SOMETIMES\n"));
  }

  @Test
  void parsesTheShippedFixtureConfig() throws Exception {
    // The .qits-config.yml committed into the testing-repo-quarkus-angular fixture must always
    // parse
    // (seed-webapp ingests it on clone). Read the real file so a bad edit fails here, not at seed.
    java.nio.file.Path file =
        java.nio.file.Path.of(
            "src/test/resources/fixtures/testing-repo-quarkus-angular/.qits-config.yml");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.Files.exists(file), "fixture submodule not checked out");
    QitsConfig config = parser.parse(java.nio.file.Files.readString(file));
    assertEquals(1, config.services().size());
    ServiceDecl daemon = config.services().get(0);
    assertEquals("Quarkus dev server", daemon.name());
    assertEquals(4200, daemon.webView().port());
    assertEquals("greeting", daemon.webView().entryPath());
    assertEquals(2, daemon.healthChecks().size());
    assertEquals(HealthCheckKind.COMMAND, daemon.healthChecks().get(0).kind());
    assertEquals(5, config.actions().size());
    assertTrue(config.actions().stream().anyMatch(a -> a.name().equals("build-project")));
    assertTrue(config.actions().stream().anyMatch(a -> a.name().equals("Stack info")));
    // The minimal check-guarded bootstrap chain — the E2E regression carrier for the feature.
    assertEquals(1, config.bootstrap().size());
    assertEquals("prepare-workspace", config.bootstrap().get(0).name());
    assertTrue(config.bootstrap().get(0).check().contains("/tmp/qits-bootstrap-marker"));
  }

  @Test
  void parsesFrameworksOverride() {
    QitsConfig config =
        parser.parse(
            """
            version: 1
            frameworks:
              - kind: java-quarkus
                root: .
              - kind: ts-angular
                root: src/main/webui
            """);
    assertEquals(2, config.frameworks().size());
    assertEquals("java-quarkus", config.frameworks().get(0).kind());
    assertEquals("src/main/webui", config.frameworks().get(1).root());
  }
}
