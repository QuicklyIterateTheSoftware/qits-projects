package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.process.control.RepoProcessLease;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The repository-scoped reattach/guard surface over HTTP: {@code POST …/pull} answers immediately
 * with a technical-process id (the recursive pull runs off-thread against the bare origins), {@code
 * GET …/active-process} resolves that id while the pull runs and turns null once it completes, a
 * second pull while one is active is single-flighted to the same id, and an unknown repository's
 * active-process is simply null (mirrors the workspace endpoint; the pull POST keeps its 404).
 */
@QuarkusTest
@TestProfile(RepositoryActiveProcessTest.TestProfile.class)
public class RepositoryActiveProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-repo-active-process-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject TechnicalProcessRegistry registry;

  private final String fixtureUrl;

  public RepositoryActiveProcessTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Repo Active Process Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, null))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  @Test
  public void pullReturnsAProcessIdAndActiveProcessTracksItsLifecycle() throws Exception {
    String repoId = createProjectAndRepository();

    String processId =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/repositories/" + repoId + "/pull")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("technicalProcessId", notNullValue())
            .extract()
            .path("technicalProcessId");

    // Await the off-thread pull's terminal done via the registry (host-side pull, no docker).
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + 15_000;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "pull did not complete in time");

    given()
        .when()
        .get("/api/repositories/" + repoId + "/active-process")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", nullValue());
  }

  @Test
  public void activeProcessResolvesTheRunningRepositoryProcessId() {
    String repoId = createProjectAndRepository();

    // A process registered out-of-band (same registry the streamed pull uses) is discoverable.
    TechnicalProcess process = fresh(registry.beginForRepository(repoId, "pull"));
    try {
      given()
          .when()
          .get("/api/repositories/" + repoId + "/active-process")
          .then()
          .statusCode(Response.Status.OK.getStatusCode())
          .body("technicalProcessId", equalTo(process.id()));
    } finally {
      process.forceFinish();
    }
  }

  @Test
  public void aSecondPullWhileOneIsActiveIsSingleFlightedToTheSameId() {
    String repoId = createProjectAndRepository();

    TechnicalProcess active = fresh(registry.beginForRepository(repoId, "pull"));
    try {
      given()
          .contentType(ContentType.JSON)
          .when()
          .post("/api/repositories/" + repoId + "/pull")
          .then()
          .statusCode(Response.Status.OK.getStatusCode())
          .body("technicalProcessId", equalTo(active.id()));
    } finally {
      active.forceFinish();
    }
  }

  @Test
  public void aSyncWhileAPullIsActiveIsRejectedRatherThanSkippingThePush() {
    String repoId = createProjectAndRepository();

    // A live pull holds the origin; a Sync can't ride it (it would skip the push), so it is a 400 —
    // not a silent success that never pushed.
    TechnicalProcess active = fresh(registry.beginForRepository(repoId, "pull"));
    try {
      given()
          .contentType(ContentType.JSON)
          .when()
          .post("/api/repositories/" + repoId + "/sync")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    } finally {
      active.forceFinish();
    }
  }

  @Test
  public void activeProcessOnAnUnknownRepositoryIs404() {
    given()
        .when()
        .get("/api/repositories/ghost/active-process")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  private static TechnicalProcess fresh(RepoProcessLease lease) {
    return ((RepoProcessLease.Fresh) lease).process();
  }
}
