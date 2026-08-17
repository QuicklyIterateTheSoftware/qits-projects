package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class RepositoryControllerTest {

  private final String fixtureUrl;

  public RepositoryControllerTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  private String createProjectAndRepository() {
    return createProjectAndRepository(fixtureUrl);
  }

  private String createProjectAndRepository(String url) {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRequest(
                    "Repo Project", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRepositoryRequest(
                url, null, eu.wohlben.qits.projects.entity.RepositoryArchetype.SERVICE))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
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
        .get("/projects/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repoId));

    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/projects/api/repositories/" + repoId)
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
        .get("/projects/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branches.name", hasItems("master", "feature"));
  }

  @Test
  public void testDeleteLeafBranch() {
    String repoId = createProjectAndRepository();

    // "feature" is a plain branch with no workspace forked from it, so it can be deleted.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "feature")
        .when()
        .delete("/projects/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branches.name", not(hasItem("feature")));
  }

  // SEAM: testDeleteBranchWithChildrenRejected is not carried over. It made a branch "have
  // children" by POSTing a workspace onto it, then asserted DELETE /branches rejects it. The child
  // check now runs through the optional WorkspaceLookup port (RepositoryService.deleteBranch), and
  // there is no route in this repo that can create the child. UNOWNED — it belongs with whichever
  // repo can create a workspace.

  @Test
  public void testDeleteBranchRejectsFlagLikeName() {
    String repoId = createProjectAndRepository();

    // A dash-leading name must not be smuggled to git as a flag.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "-D")
        .when()
        .delete("/projects/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testPullReturnsTechnicalProcessIdImmediately() {
    String repoId = createProjectAndRepository();

    // Pull is asynchronous: the POST registers a technical process and returns its id right away —
    // the recursive walk streams over the process's SSE, not the HTTP response.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.PullRepositoryRequest())
        .when()
        .post("/projects/api/repositories/" + repoId + "/pull")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", not(emptyOrNullString()));
  }

  @Test
  public void testPullUnknownRepositoryIs404InRequest() {
    // The repo id is validated in-request before any process is registered, so a bad id is a plain
    // 404, not a streamed failure.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.PullRepositoryRequest())
        .when()
        .post("/projects/api/repositories/does-not-exist/pull")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testPushReturnsTechnicalProcessIdImmediately() {
    String repoId = createProjectAndRepository();

    // Push is asynchronous like pull/sync: the POST registers a technical process and returns its
    // id right away — the single push segment streams over the process's SSE, not the HTTP
    // response.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.PushRepositoryRequest())
        .when()
        .post("/projects/api/repositories/" + repoId + "/push")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", not(emptyOrNullString()));
  }

  @Test
  public void testPushUnknownRepositoryIs404InRequest() {
    // The repo id is validated in-request before any process is registered, so a bad id is a plain
    // 404, not a streamed failure.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.PushRepositoryRequest())
        .when()
        .post("/projects/api/repositories/does-not-exist/push")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testSyncReturnsTechnicalProcessIdImmediately() {
    String repoId = createProjectAndRepository();

    // Sync is asynchronous like pull: the POST registers a technical process and returns its id
    // right away — the pull walk plus the final push stream over the process's SSE, not the HTTP
    // response.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.SyncRepositoryRequest())
        .when()
        .post("/projects/api/repositories/" + repoId + "/sync")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", not(emptyOrNullString()));
  }

  @Test
  public void testSyncUnknownRepositoryIs404InRequest() {
    // The repo id is validated in-request before any process is registered, so a bad id is a plain
    // 404, not a streamed failure.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.SyncRepositoryRequest())
        .when()
        .post("/projects/api/repositories/does-not-exist/sync")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testGetRepositoryDefaultsMainBranchToRemoteHead() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId)
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
        .get("/projects/api/repositories/" + repoId + "/sync-status")
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
        .put("/projects/api/repositories/" + repoId + "/main-branch")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.mainBranch", equalTo("feature"));

    // The sync status now tracks the newly configured branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/sync-status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("feature"));
  }

  @Test
  public void testSyncStatusReportsBehindWhenRemoteAdvances() throws Exception {
    // A writable bare remote we can advance (the shared fixture itself must stay immutable).
    Path remote = Files.createTempDirectory("qits-remote");
    runGit(null, "git", "clone", "--bare", fixtureUrl, remote.toString());

    String repoId = createProjectAndRepository(remote.toString());

    // After the app has mirrored the remote, push a new commit to it. The mirror is now one
    // commit behind AND lacks that commit's objects — the condition that previously made the
    // ahead/behind counts come back null, which the UI rendered as "up to date with remote".
    Path work = Files.createTempDirectory("qits-work");
    runGit(null, "git", "clone", remote.toString(), work.toString());
    runGit(
        work,
        "git",
        "-c",
        "user.email=test@example.com",
        "-c",
        "user.name=Test",
        "commit",
        "--allow-empty",
        "-m",
        "remote-only commit");
    runGit(work, "git", "push", "origin", "HEAD:master");

    // sync-status now fetches the missing objects and reports the true count.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/sync-status")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("remoteReachable", equalTo(true))
        .body("remoteExists", equalTo(true))
        .body("ahead", equalTo(0))
        .body("behind", equalTo(1));
  }

  private String runGit(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new RuntimeException("git failed: " + String.join(" ", command) + "\n" + output);
    }
    return output;
  }

  @Test
  public void testSetMainBranchRejectsUnknownBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.SetMainBranchRequest("does-not-exist"))
        .when()
        .put("/projects/api/repositories/" + repoId + "/main-branch")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  /**
   * The by-id read answers both callers its two roles name: a machine holding {@code qits:system}
   * alone — qits-workspaces' {@code RepositoryLookup}, which is how a release learns which
   * repository it is releasing — and a browser session holding {@code qits:admin} alone, which is
   * the workspaces detail screen reading the repository's main branch on a deep link.
   *
   * <p>The method-level roles REPLACE the controller's class-level {@code qits:admin}, so naming
   * only the system role refused every browser that reached this route: the same defect the
   * project's repositories listing had, one route over.
   */
  @Test
  public void bothAMachineAndABrowserSessionReadOneRepositoryById() {
    String repoId = createProjectAndRepository();

    given()
        .header("X-Qits-User", "dev-qits-workspaces")
        .header("X-Qits-Roles", "qits:system")
        .when()
        .get("/projects/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repoId));

    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/projects/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repoId));
  }

  // SEAM (migration-plan.md §6, repository <-> workspace). Fourteen tests stood here, all over
  // POST /repositories/{id}/branches/merge and .../branches/cleanup — the two routes that forwarded
  // to WorkspaceService and are cut from RepositoryController (see the seam note there):
  // testIntegrateBranchDefaultsToMainBranch, testIntegrateBranchIntoExplicitTarget,
  // testIntegrateRejectsBranchIntoItself, testIntegrateRejectsFlagLikeSource,
  // testIntegrateRequiresSource, testIntegrateUnknownRepoReturns404,
  // testIntegrateAutoCleansUpEligibleWorkspace, testIntegrateKeepsWorkspaceWithChildren,
  // testIntegratePlainBranchAutoCleansUp, testBranchesReportCanCleanup,
  // testCleanupBranchRemovesEligibleWorkspace, testCleanupBranchRejectsUnmergedCommits,
  // testCleanupBranchRejectsBranchWithChildren, testCleanupBranchRejectsMainBranch and
  // testCleanupBranchRequiresBranch.
  //
  // All but one moved with the routes, to qits-workspaces' BranchControllerTest. The exception is
  // testBranchesReportCanCleanup, which asserts the canCleanup flag of GET /branches — a route this
  // controller still owns, computing the flag through the WorkspaceLookup SPI. It has no home yet.
}
