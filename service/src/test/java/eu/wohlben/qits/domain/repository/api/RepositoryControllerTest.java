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
    return createProjectAndRepository(fixtureUrl);
  }

  private String createProjectAndRepository(String url) {
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
                url, null))
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
        .body("branches.name", not(hasItem("feature")));
  }

  @Test
  public void testDeleteBranchWithChildrenRejected() {
    String repoId = createProjectAndRepository();

    // Fork a workspace from "feature": now a workspace's parent points at "feature".
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(
                "child-wt", "feature", "child-branch", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Deleting "feature" would orphan that workspace, so it is rejected.
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
        .get("/api/repositories/" + repoId + "/sync-status")
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
        .put("/api/repositories/" + repoId + "/main-branch")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateBranchDefaultsToMainBranch() {
    String repoId = createProjectAndRepository();

    // No target given → integrate "feature" into the repo's configured main branch (master).
    // feature only adds feature.txt relative to the merge base, so this is a clean merge.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("feature", null, null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commitHash", not(emptyOrNullString()))
        .body("hasConflicts", equalTo(false));
  }

  @Test
  public void testIntegrateBranchIntoExplicitTarget() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commitHash", not(emptyOrNullString()));
  }

  @Test
  public void testIntegrateRejectsBranchIntoItself() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("master", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateRejectsFlagLikeSource() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("-D", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateRequiresSource() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateUnknownRepoReturns404() {
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/api/repositories/does-not-exist/branches/merge")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  private void createWorkspace(String repoId, String id, String parent, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(id, parent, branch, null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void testIntegrateAutoCleansUpEligibleWorkspace() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "auto-wt", "master", "auto-b");

    // Integrating a clean, dependent-free workspace into its parent removes it afterwards.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("auto-b", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false))
        .body("cleanedUp", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", not(hasItem("auto-wt")));
  }

  @Test
  public void testIntegrateKeepsWorkspaceWithChildren() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "pwt", "master", "pb");
    createWorkspace(repoId, "cwt", "pb", "cb");

    // pb still has a dependent workspace (cwt), so it must not be cleaned up after integration.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("pb", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("cleanedUp", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", hasItem("pwt"));
  }

  @Test
  public void testIntegratePlainBranchAutoCleansUp() {
    String repoId = createProjectAndRepository();

    // "feature" is a plain branch (no workspace). Integrating it into master leaves it fully merged
    // with no dependents, so it is deleted afterwards — the same behaviour as a workspace branch.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false))
        .body("cleanedUp", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branches.name", not(hasItem("feature")));
  }

  @Test
  public void testBranchesReportCanCleanup() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "cc-wt", "master", "cc-b");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/branches")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        // a fresh fork is fully merged, clean and dependent-free → cleanable
        .body("branches.find { it.name == 'cc-b' }.canCleanup", equalTo(true))
        // the main branch is never cleanable, and feature has unmerged commits
        .body("branches.find { it.name == 'master' }.canCleanup", equalTo(false))
        .body("branches.find { it.name == 'feature' }.canCleanup", equalTo(false));
  }

  @Test
  public void testCleanupBranchRemovesEligibleWorkspace() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "elig-wt", "master", "elig-b");

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.CleanupBranchRequest("elig-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", not(hasItem("elig-wt")));
  }

  @Test
  public void testCleanupBranchRejectsUnmergedCommits() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "ahead-wt", "master", "ahead-b");
    // Advance ahead-b past master by integrating the diverged feature branch into it.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.MergeBranchRequest("feature", "ahead-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.CleanupBranchRequest("ahead-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRejectsBranchWithChildren() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "par-wt", "master", "par-b");
    createWorkspace(repoId, "chi-wt", "par-b", "chi-b");

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.CleanupBranchRequest("par-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRejectsMainBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.CleanupBranchRequest("master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRequiresBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.CleanupBranchRequest("", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
