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
@TestProfile(WorktreeControllerTest.TestProfile.class)
public class WorktreeControllerTest {

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

  public WorktreeControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Worktree Project", null))
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
  public void testCreateWorktreeAndMergeAndDiscard() {
    String repoId = createProjectAndRepository();

    // fork a new branch "step-work" from the feature branch
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("step-01", "feature", "step-work"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("worktree.worktreeId", equalTo("step-01"));

    // merge the worktree's branch into master
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.MergeWorktreeRequest("master"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/step-01/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false));

    // discard
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.DiscardWorktreeRequest())
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/step-01/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }

  @Test
  public void testListWorktreesReturnsCreatedWorktreeWithBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("wt-list", "master", "wt-branch"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.worktree.worktreeId", hasItem("wt-list"))
        // branch is the worktree's own forked branch, resolved from the on-disk worktree
        // (also a regression guard for the path fix).
        .body(
            "entries.find { it.worktree.worktreeId == 'wt-list' }.worktree.branch",
            equalTo("wt-branch"));
  }

  @Test
  public void testTwoWorktreesCanForkFromTheSameParentBranch() {
    String repoId = createProjectAndRepository();

    // Two worktrees forking new branches from the same parent must not conflict —
    // the old behaviour (checking out an existing branch) made this impossible.
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("fork-a", "master", "branch-a"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("fork-b", "master", "branch-b"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.worktree.worktreeId == 'fork-a' }.worktree.branch",
            equalTo("branch-a"))
        .body(
            "entries.find { it.worktree.worktreeId == 'fork-b' }.worktree.branch",
            equalTo("branch-b"));
  }

  @Test
  public void testListWorktreesReportsCommitsAheadAndBehindParent() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest("ab-wt", "master", "ab-branch"))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // A worktree freshly forked from its parent has made no commits yet, so it is
    // neither ahead of nor behind the parent branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.worktree.worktreeId == 'ab-wt' }.worktree.ahead", equalTo(0))
        .body("entries.find { it.worktree.worktreeId == 'ab-wt' }.worktree.behind", equalTo(0));
  }

  private void createWorktree(String repoId, String id, String parent, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.CreateWorktreeRequest(id, parent, branch))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  private void mergeInto(String repoId, String worktreeId, String target) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorktreeController.MergeWorktreeRequest(target))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/" + worktreeId + "/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void testFastForwardAdvancesBranchToParent() {
    String repoId = createProjectAndRepository();

    // parent-wt owns parent-branch (== master); child-wt forks child-branch off it, so the two
    // start at the same commit.
    createWorktree(repoId, "parent-wt", "master", "parent-branch");
    createWorktree(repoId, "child-wt", "parent-branch", "child-branch");

    // Advance parent-branch by merging the (diverged) feature branch into it. child-branch now
    // lags strictly behind parent-branch with no commits of its own — a clean fast-forward.
    createWorktree(repoId, "src-wt", "feature", "src-branch");
    mergeInto(repoId, "src-wt", "parent-branch");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.worktree.worktreeId == 'child-wt' }.worktree.behind", greaterThan(0))
        .body("entries.find { it.worktree.worktreeId == 'child-wt' }.worktree.ahead", equalTo(0));

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/child-wt/fast-forward")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // After fast-forwarding, child-branch sits on parent-branch's tip: no longer ahead or behind.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.worktree.worktreeId == 'child-wt' }.worktree.behind", equalTo(0))
        .body("entries.find { it.worktree.worktreeId == 'child-wt' }.worktree.ahead", equalTo(0));
  }

  @Test
  public void testFastForwardRejectsDivergedBranch() {
    String repoId = createProjectAndRepository();

    createWorktree(repoId, "dv-parent", "master", "dv-parent-branch");
    createWorktree(repoId, "dv-child", "dv-parent-branch", "dv-child-branch");
    createWorktree(repoId, "dv-src", "feature", "dv-src-branch");

    // Merge feature into both branches independently: each gets its own merge commit, so the
    // child branch ends up both ahead of and behind its parent — a fast-forward can't apply.
    mergeInto(repoId, "dv-src", "dv-parent-branch");
    mergeInto(repoId, "dv-src", "dv-child-branch");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/dv-child/fast-forward")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testListWorktreesEmptyForFreshRepository() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(0));
  }
}
