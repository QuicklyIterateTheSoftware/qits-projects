package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The streamed Start over HTTP: {@code POST …/ensure-container} answers immediately with a
 * technical-process id (the provision runs off-thread against {@link
 * eu.wohlben.qits.domain.repository.control.FakeContainerRuntime}), {@code GET …/active-process}
 * resolves that id while the process runs and turns null once it completes, and an unknown
 * workspace still 404s in-request.
 */
@QuarkusTest
@TestProfile(WorkspaceActiveProcessTest.TestProfile.class)
public class WorkspaceActiveProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-active-process-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject TechnicalProcessRegistry registry;

  private final String fixtureUrl;

  public WorkspaceActiveProcessTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Active Process Project", null))
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
  public void ensureContainerReturnsAProcessIdAndActiveProcessTracksItsLifecycle()
      throws Exception {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("proc", "master", "proc-work", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    String processId =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post("/api/repositories/" + repoId + "/workspaces/proc/ensure-container")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("technicalProcessId", notNullValue())
            .body("workspace.workspaceId", equalTo("proc"))
            .extract()
            .path("technicalProcessId");

    // Await the off-thread provision's terminal done via the registry (no docker: fake runtime).
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + 15_000;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "provision did not complete in time");

    given()
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/proc/active-process")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", nullValue());
  }

  @Test
  public void activeProcessResolvesTheRunningProcessId() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("live", "master", "live-work", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // A process registered out-of-band (same registry the streamed Start uses) is discoverable.
    TechnicalProcess process = registry.begin(repoId, "live");
    given()
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/live/active-process")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", equalTo(process.id()));
    process.forceFinish();
  }

  @Test
  public void ensureContainerOnAnUnknownWorkspaceIs404InRequest() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/ghost/ensure-container")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }
}
