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

  // The effective data dir the app uses, so tests can commit directly inside a worktree on disk.
  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

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
  public void testDivergedButCleanWorktreeReportsNoConflict() throws Exception {
    String repoId = createProjectAndRepository();

    createWorktree(repoId, "clean-parent", "master", "clean-parent-branch");
    createWorktree(repoId, "clean-child", "clean-parent-branch", "clean-child-branch");

    // Both branches add their own distinct file, so each is ahead of and behind the other, yet
    // merging the parent in applies cleanly — divergence without a conflict.
    commitFile(repoId, "clean-parent", "parent-only.txt", "from parent\n", "parent commit");
    commitFile(repoId, "clean-child", "child-only.txt", "from child\n", "child commit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.worktree.worktreeId == 'clean-child' }.worktree.ahead",
            greaterThan(0))
        .body(
            "entries.find { it.worktree.worktreeId == 'clean-child' }.worktree.behind",
            greaterThan(0))
        // diverged but a merge would apply cleanly → no conflict warning
        .body(
            "entries.find { it.worktree.worktreeId == 'clean-child' }.worktree.conflictsWithParent",
            equalTo(false));
  }

  @Test
  public void testDivergedConflictingWorktreeReportsConflict() throws Exception {
    String repoId = createProjectAndRepository();

    createWorktree(repoId, "cf-parent", "master", "cf-parent-branch");
    createWorktree(repoId, "cf-child", "cf-parent-branch", "cf-child-branch");

    // Both branches change the same line of the same file to different values, so a merge of the
    // parent into the child can't apply without manual resolution.
    commitFile(repoId, "cf-parent", "conflict.txt", "parent version\n", "parent edit");
    commitFile(repoId, "cf-child", "conflict.txt", "child version\n", "child edit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.worktree.worktreeId == 'cf-child' }.worktree.ahead", greaterThan(0))
        .body(
            "entries.find { it.worktree.worktreeId == 'cf-child' }.worktree.behind", greaterThan(0))
        .body(
            "entries.find { it.worktree.worktreeId == 'cf-child' }.worktree.conflictsWithParent",
            equalTo(true));
  }

  @Test
  public void testUpdateFromParentMergesDivergedBranch() throws Exception {
    String repoId = createProjectAndRepository();

    createWorktree(repoId, "up-parent", "master", "up-parent-branch");
    createWorktree(repoId, "up-child", "up-parent-branch", "up-child-branch");

    // Diverge cleanly: each branch adds its own distinct file.
    commitFile(repoId, "up-parent", "parent-only.txt", "from parent\n", "parent commit");
    commitFile(repoId, "up-child", "child-only.txt", "from child\n", "child commit");

    // A fast-forward can't apply (the child has its own commit), but a merge can.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/up-child/update-from-parent")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // After merging the parent in, the child contains the parent's commit, so it's no longer
    // behind.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.worktree.worktreeId == 'up-child' }.worktree.behind", equalTo(0));
  }

  @Test
  public void testUpdateFromParentRejectsConflictAndLeavesWorktreeUsable() throws Exception {
    String repoId = createProjectAndRepository();

    createWorktree(repoId, "uc-parent", "master", "uc-parent-branch");
    createWorktree(repoId, "uc-child", "uc-parent-branch", "uc-child-branch");

    // Both edit the same line: a merge of the parent into the child would conflict.
    commitFile(repoId, "uc-parent", "conflict.txt", "parent version\n", "parent edit");
    commitFile(repoId, "uc-child", "conflict.txt", "child version\n", "child edit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/uc-child/update-from-parent")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());

    // The aborted merge must leave the worktree exactly as it was: still diverged, not mid-merge.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.worktree.worktreeId == 'uc-child' }.worktree.behind", greaterThan(0))
        .body(
            "entries.find { it.worktree.worktreeId == 'uc-child' }.worktree.ahead", greaterThan(0));
  }

  /** Writes a file inside the worktree on disk and commits it on the worktree's branch. */
  private void commitFile(String repoId, String worktreeId, String file, String content, String msg)
      throws Exception {
    Path worktreePath = Path.of(dataDir, repoId, "worktrees", worktreeId);
    Files.writeString(worktreePath.resolve(file), content);
    runGit(worktreePath, "git", "add", file);
    runGit(
        worktreePath,
        "git",
        "-c",
        "user.email=test@example.com",
        "-c",
        "user.name=Test",
        "commit",
        "-m",
        msg);
  }

  private void runGit(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0) {
      String out = new String(p.getInputStream().readAllBytes());
      throw new RuntimeException("git " + String.join(" ", command) + " failed: " + out);
    }
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

  @Test
  public void testCreateWorktreeRejectsPathTraversalAndFlagIds() {
    String repoId = createProjectAndRepository();
    // A worktree id becomes a path segment + git operand; slashes/dots/leading-dash are rejected.
    for (String badId : new String[] {"../escape", "a/b", "-D", "."}) {
      given()
          .contentType(ContentType.JSON)
          .body(new WorktreeController.CreateWorktreeRequest(badId, "master", "wt-branch"))
          .when()
          .post("/api/repositories/" + repoId + "/worktrees")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }
  }
}
