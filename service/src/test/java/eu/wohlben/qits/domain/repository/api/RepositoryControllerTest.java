package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RepositoryControllerTest.TestProfile.class)
public class RepositoryControllerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private final String fixtureUrl;

  public RepositoryControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Repo Project", null))
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
                fixtureUrl, null))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  @Test
  public void testGetAndDelete() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repoId));

    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }

  @Test
  public void testListBranches() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branches", hasItems("master", "feature"));
  }

  @Test
  public void testDeleteLeafBranch() {
    String repoId = createProjectAndRepository();

    // "feature" is a plain branch with no worktree forked from it, so it can be deleted.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "feature")
        .when()
        .delete("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branches", not(hasItem("feature")));
  }

  @Test
  public void testDeleteBranchWithChildrenRejected() {
    String repoId = createProjectAndRepository();

    // Fork a worktree from "feature": now a worktree's parent points at "feature".
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("child-wt", "feature", "child-branch"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Deleting "feature" would orphan that worktree, so it is rejected.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "feature")
        .when()
        .delete("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testDeleteBranchRejectsFlagLikeName() {
    String repoId = createProjectAndRepository();

    // A dash-leading name must not be smuggled to git as a flag.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "-D")
        .when()
        .delete("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testPullAndPush() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.PullRepositoryRequest())
        .when()
        .post("/api/repositories/" + repoId + "/pull")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.PushRepositoryRequest())
        .when()
        .post("/api/repositories/" + repoId + "/push")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void testSyncPullsThenPushes() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.SyncRepositoryRequest())
        .when()
        .post("/api/repositories/" + repoId + "/sync")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void testGetRepositoryDefaultsMainBranchToRemoteHead() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.mainBranch", equalTo("master"));
  }

  @Test
  public void testSyncStatusInSyncForFreshClone() {
    String repoId = createProjectAndRepository();

    // A fresh mirror clone matches its remote exactly.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/sync-status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("master"))
        .body("remoteReachable", equalTo(true))
        .body("remoteExists", equalTo(true))
        .body("ahead", equalTo(0))
        .body("behind", equalTo(0));
  }

  @Test
  public void testSetMainBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.SetMainBranchRequest("feature"))
        .when()
        .put("/api/repositories/" + repoId + "/main-branch")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.mainBranch", equalTo("feature"));

    // The sync status now tracks the newly configured branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/sync-status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("feature"));
  }

  @Test
  public void testSetMainBranchRejectsUnknownBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.SetMainBranchRequest("does-not-exist"))
        .when()
        .put("/api/repositories/" + repoId + "/main-branch")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
