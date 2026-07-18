package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.HealthCheckKind;
import eu.wohlben.qits.domain.daemon.entity.LogObserverKind;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.repository.control.QitsConfig.ActionDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfig.DaemonDecl;
import eu.wohlben.qits.domain.repository.control.QitsConfigParser.QitsConfigException;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
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
                observers:
                  - kind: LOG_LEVEL
                  - kind: PATTERN
                    pattern: "(?i)BUILD FAILURE"
                    severity: ERROR
                sources:
                  - path: quarkus.log
                    label: Quarkus dev log
                health-checks:
                  - name: Quarkus
                    kind: HTTP
                    port: 8080
                    path: /q/health
                    expect-status: 2xx,3xx
                    interval-ms: 5000
            """);
    assertEquals(1, config.daemons().size());
    DaemonDecl d = config.daemons().get(0);
    assertEquals("dev-server", d.name());
    assertEquals("./mvnw quarkus:dev", d.start());
    assertEquals("Listening on", d.readyPattern());
    assertEquals(Boolean.TRUE, d.otel());
    assertEquals(RestartPolicy.ON_FAILURE, d.restartPolicy());
    assertEquals(3, d.maxRestarts());
    assertEquals("0.0.0.0", d.environment().get("QUARKUS_HTTP_HOST"));
    assertEquals(4200, d.webView().port());
    assertEquals("/", d.webView().entryPath());
    assertEquals(2, d.observers().size());
    assertEquals(LogObserverKind.LOG_LEVEL, d.observers().get(0).kind());
    assertEquals(LogObserverKind.PATTERN, d.observers().get(1).kind());
    assertEquals(DaemonEventSeverity.ERROR, d.observers().get(1).severity());
    assertEquals("quarkus.log", d.sources().get(0).path());
    assertEquals("Quarkus dev log", d.sources().get(0).label());
    assertEquals(HealthCheckKind.HTTP, d.healthChecks().get(0).kind());
    assertEquals(8080, d.healthChecks().get(0).port());
    assertEquals(Long.valueOf(5000), d.healthChecks().get(0).intervalMs());
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
    assertEquals(1, config.daemons().size());
    DaemonDecl daemon = config.daemons().get(0);
    assertEquals("Quarkus dev server", daemon.name());
    assertEquals(4200, daemon.webView().port());
    assertEquals("greeting", daemon.webView().entryPath());
    assertEquals(2, daemon.observers().size());
    assertEquals(1, daemon.sources().size());
    assertEquals(2, daemon.healthChecks().size());
    assertEquals(HealthCheckKind.COMMAND, daemon.healthChecks().get(0).kind());
    assertEquals(5, config.actions().size());
    assertTrue(config.actions().stream().anyMatch(a -> a.name().equals("build-project")));
    assertTrue(config.actions().stream().anyMatch(a -> a.name().equals("Stack info")));
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
