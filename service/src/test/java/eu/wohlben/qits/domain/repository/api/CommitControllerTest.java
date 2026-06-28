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
@TestProfile(CommitControllerTest.TestProfile.class)
public class CommitControllerTest {

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

  public CommitControllerTest() throws Exception {
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
  public void testCommitsForPlainBranchUseMainBranchAsParent() {
    String repoId = createProjectAndRepository();

    // "feature" is a plain branch (no worktree), so it is compared against the main branch
    // (master). Only the single commit unique to "feature" is returned.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "feature")
        .when()
        .get("/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("feature"))
        .body("parent", equalTo("master"))
        .body("commits.size()", equalTo(1))
        .body("commits[0].message", equalTo("Add feature.txt"))
        .body("commits[0].hash", not(emptyOrNullString()))
        .body("commits[0].shortHash", not(emptyOrNullString()))
        .body("commits[0].author", not(emptyOrNullString()))
        .body("commits[0].date", not(emptyOrNullString()))
        // the paths the commit changed, parsed from `git log --name-only`
        .body("commits[0].files", hasItem("feature.txt"));
  }

  @Test
  public void testCommitsForMainBranchFallBackToFullHistory() {
    String repoId = createProjectAndRepository();

    // The main branch's parent resolves to itself, so the range degrades to the full history.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "master")
        .when()
        .get("/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("master"))
        .body("parent", nullValue())
        .body("commits.size()", equalTo(3));
  }

  @Test
  public void testCommitsForWorktreeBranchUseWorktreeParent() {
    String repoId = createProjectAndRepository();

    // Fork a worktree off "feature": its new branch's parent is "feature".
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("child-wt", "feature", "child-branch"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // No commits added on the worktree yet, so feature..child-branch is empty, but the parent
    // is resolved from the worktree rather than the main branch.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "child-branch")
        .when()
        .get("/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("child-branch"))
        .body("parent", equalTo("feature"))
        .body("commits.size()", equalTo(0));
  }

  @Test
  public void testCommitsRejectFlagLikeBranchName() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "-D")
        .when()
        .get("/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitsRequireBranchParam() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitChangesNoParentListsFilesChangedInCommit() {
    String repoId = createProjectAndRepository();

    // Without a parent the changes are computed against the commit's own first parent: the
    // "Add feature.txt" commit only added feature.txt. The resolved base is null (no explicit
    // parent was given).
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/commits/feature/changes")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commit", equalTo("feature"))
        .body("parent", nullValue())
        .body("files.size()", equalTo(1))
        .body("files[0].path", equalTo("feature.txt"))
        .body("files[0].changeType", equalTo("ADDED"))
        .body("files[0].oldPath", nullValue());
  }

  @Test
  public void testCommitChangesWithExplicitParentRebasesDiff() {
    String repoId = createProjectAndRepository();

    // Diffing feature against master surfaces both feature.txt (added) and README.md (modified,
    // since master advanced the README after feature forked).
    given()
        .contentType(ContentType.JSON)
        .queryParam("parent", "master")
        .when()
        .get("/api/repositories/" + repoId + "/commits/feature/changes")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("parent", equalTo("master"))
        .body("files.size()", equalTo(2))
        .body("files.path", hasItems("README.md", "feature.txt"));
  }

  @Test
  public void testCommitFileDiffReturnsUnifiedPatch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .queryParam("path", "feature.txt")
        .when()
        .get("/api/repositories/" + repoId + "/commits/feature/diff")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("path", equalTo("feature.txt"))
        .body("changeType", equalTo("ADDED"))
        .body("diff", containsString("+feature work"))
        .body("diff", containsString("diff --git"));
  }

  @Test
  public void testCommitChangesRejectFlagLikeCommit() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/commits/-D/changes")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitFileDiffRejectFlagLikePath() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .queryParam("path", "-rf")
        .when()
        .get("/api/repositories/" + repoId + "/commits/feature/diff")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitFileDiffRequiresPathParam() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/commits/feature/diff")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitChangesUnknownRepoReturns404() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/does-not-exist/commits/feature/changes")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }
}
