package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class CommitControllerTest {

  private final String fixtureUrl;

  public CommitControllerTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRequest(
                    "Repo Project", null, null, null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, null))
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

    // "feature" is a plain branch (no workspace), so it is compared against the main branch
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

  // SEAM (migration-plan.md §6): testCommitsForWorkspaceBranchUseWorkspaceParent is not carried over — it POSTed a workspace
  // through the (now qits-workspaces') /repositories/{id}/workspaces route to give the branch a
  // parent, then asserted the commit log was scoped to it. The parent lookup is the optional
  // WorkspaceLookup port now, and no route here can create a workspace. UNOWNED.
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
