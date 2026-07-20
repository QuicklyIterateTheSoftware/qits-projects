package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.repository.control.WorkspacePromptDraftService;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptDraftRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import eu.wohlben.qits.domain.workspace.api.HintCollector;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint.Topic;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(WorkspacePromptDraftControllerTest.TestProfile.class)
public class WorkspacePromptDraftControllerTest {

  /** A tiny {@code prompt-draft-max-bytes} cap so the oversize case needs only a small payload. */
  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of(
            "qits.repositories.data-dir",
            tempDir.toString(),
            "qits.workspace.prompt-draft-max-bytes",
            "64");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspacePromptDraftRepository draftRepository;

  @Inject WorkspacePromptDraftService promptDraftService;

  @Inject HintCollector hintCollector;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private final String fixtureUrl;

  public WorkspacePromptDraftControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Prompt Draft Project", null))
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

  /** The draft endpoint for a workspace (the fresh repo always has a "master" main workspace). */
  private String draftUrl(String repoId, String workspaceId) {
    return "/api/repositories/" + repoId + "/workspaces/" + workspaceId + "/prompt-draft";
  }

  /**
   * Drain the async hint bus until a {@link Topic#PROMPT_DRAFT} hint for {@code repoId} arrives
   * (ignoring unrelated hints from fixture setup), or time out. Clear the collector right before
   * the mutation under test so this only sees the hint that mutation fired.
   */
  private WorkspaceChangeHint awaitPromptDraftHint(String repoId, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long remaining;
    while ((remaining = deadline - System.currentTimeMillis()) > 0) {
      WorkspaceChangeHint hint = hintCollector.poll(remaining);
      if (hint == null) {
        return null;
      }
      if (hint.repoId().equals(repoId) && hint.topic() == Topic.PROMPT_DRAFT) {
        return hint;
      }
    }
    return null;
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
  public void getReturns404WhenNoDraftExists() {
    String repoId = createProjectAndRepository();

    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void putThenGetRoundTripsContentAndSerializedPrompt() {
    String repoId = createProjectAndRepository();
    String content = "{\"v\":1,\"t\":\"hi\"}";
    String serialized = "# Do the thing\n\nplease";

    String updatedAt =
        given()
            .contentType(ContentType.JSON)
            .body(new WorkspacePromptDraftController.SaveDraftRequest(content, serialized))
            .when()
            .put(draftUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("updatedAt", not(emptyOrNullString()))
            .extract()
            .path("updatedAt");

    // Byte-identical round-trip of both the opaque blob and the server-readable markdown.
    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("content", equalTo(content))
        .body("serializedPrompt", equalTo(serialized))
        .body("updatedAt", equalTo(updatedAt));
  }

  @Test
  public void putUpsertsOverwritingTheExistingDraftMonotonically() {
    String repoId = createProjectAndRepository();

    String first =
        given()
            .contentType(ContentType.JSON)
            .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "one"))
            .when()
            .put(draftUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("updatedAt");

    String second =
        given()
            .contentType(ContentType.JSON)
            .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":2}", "two"))
            .when()
            .put(draftUrl(repoId, "master"))
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("updatedAt");

    // The second PUT overwrote the first (still one row), and the timestamp never went backwards.
    assertFalse(Instant.parse(second).isBefore(Instant.parse(first)));
    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("content", equalTo("{\"v\":2}"))
        .body("serializedPrompt", equalTo("two"));
  }

  @Test
  public void concurrentFirstSavesUpsertWithoutRacingToA500() throws Exception {
    String repoId = createProjectAndRepository();

    // Two+ clients (two tabs / two devices — the exact cross-device flow the feature targets)
    // autosave the *very first* draft for a draftless workspace at the same instant. Before the
    // atomic MERGE upsert, all of them found no row, all built the same shared PK, and every insert
    // but the winner's 500'd on the primary-key violation (dropping that autosave). Regression: the
    // upsert serializes them under the row lock, so every concurrent first-save is a clean 200.
    int n = 8;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        final int v = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await(); // release all requests together to maximize the collision window
                  return given()
                      .contentType(ContentType.JSON)
                      .body(
                          new WorkspacePromptDraftController.SaveDraftRequest(
                              "{\"v\":" + v + "}", "p" + v))
                      .when()
                      .put(draftUrl(repoId, "master"))
                      .then()
                      .extract()
                      .statusCode();
                }));
      }
      ready.await();
      go.countDown();
      for (Future<Integer> f : futures) {
        int status = f.get(20, TimeUnit.SECONDS);
        Assertions.assertEquals(
            200,
            status,
            "a concurrent first-save must upsert cleanly, not error (got " + status + ")");
      }
    } finally {
      pool.shutdownNow();
    }

    // Exactly one row survived, holding one of the concurrent writes (last-write-wins).
    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("content", startsWith("{\"v\":"))
        .body("serializedPrompt", startsWith("p"));
  }

  /** PUT a draft for a workspace, asserting the 200. */
  private void putDraft(String repoId, String workspaceId, String content, String serialized) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest(content, serialized))
        .when()
        .put(draftUrl(repoId, workspaceId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void draftExposesAMonotonicVersionAndUnsetRunTrackingUntilRun() {
    String repoId = createProjectAndRepository();
    putDraft(repoId, "master", "{\"v\":1}", "one");

    // First save = version 1; nothing has been handed to an agent yet.
    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("promptVersion", equalTo(1))
        .body("lastRunAt", nullValue())
        .body("lastRunPromptVersion", nullValue())
        .body("lastRunCommandId", nullValue());

    // A content-changing save bumps the version monotonically.
    putDraft(repoId, "master", "{\"v\":2}", "two");
    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("promptVersion", equalTo(2));
  }

  @Test
  public void recordRunStampsTheDeliveredVersionAndCommand() {
    String repoId = createProjectAndRepository();
    putDraft(repoId, "master", "{\"v\":1}", "the task");

    // A composed prompt is deliverable, and recording a run stamps the version + command that got
    // it.
    assertTrue(promptDraftService.hasDeliverablePrompt(repoId, "master"));
    promptDraftService.recordRun(repoId, "master", "cmd-xyz");

    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("lastRunPromptVersion", equalTo(1))
        .body("lastRunCommandId", equalTo("cmd-xyz"))
        .body("lastRunAt", not(emptyOrNullString()));
  }

  @Test
  public void hasDeliverablePromptIsFalseWithoutMarkdownOrAttachments() {
    String repoId = createProjectAndRepository();
    // No draft row at all.
    assertFalse(promptDraftService.hasDeliverablePrompt(repoId, "master"));
    // A draft whose serialized prompt is blank and which has no attachments is not deliverable.
    putDraft(repoId, "master", "{\"v\":1}", "   ");
    assertFalse(promptDraftService.hasDeliverablePrompt(repoId, "master"));
  }

  @Test
  public void deleteRemovesTheDraft() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "x"))
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .when()
        .delete(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());

    given()
        .when()
        .get(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void putFiresAPromptDraftHint() throws InterruptedException {
    String repoId = createProjectAndRepository();
    // Clear fixture-setup noise so the drain only sees the hint this PUT fires.
    hintCollector.clear();

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "x"))
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    WorkspaceChangeHint hint = awaitPromptDraftHint(repoId, 2000);
    org.junit.jupiter.api.Assertions.assertNotNull(hint, "PUT should fire a PROMPT_DRAFT hint");
    org.junit.jupiter.api.Assertions.assertEquals("master", hint.workspaceId());
  }

  @Test
  public void deleteFiresAPromptDraftHint() throws InterruptedException {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "x"))
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    hintCollector.clear();

    given()
        .when()
        .delete(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.NO_CONTENT.getStatusCode());

    WorkspaceChangeHint hint = awaitPromptDraftHint(repoId, 2000);
    org.junit.jupiter.api.Assertions.assertNotNull(hint, "DELETE should fire a PROMPT_DRAFT hint");
    org.junit.jupiter.api.Assertions.assertEquals("master", hint.workspaceId());
  }

  @Test
  public void oversizeContentReturns413() {
    String repoId = createProjectAndRepository();
    // Valid JSON, but its `content` exceeds the 64-byte test cap.
    String big = "{\"v\":1,\"x\":\"" + "a".repeat(80) + "\"}";

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest(big, null))
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
  }

  @Test
  public void malformedJsonReturns400() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{ not json", null))
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void emptyAndTrailingTokenContentReturn400() {
    String repoId = createProjectAndRepository();

    // Empty content is not a JSON document, and a complete value followed by garbage is malformed —
    // both must be rejected, not silently stored (readTree would have accepted the empty one and
    // stopped at the first value of the trailing one).
    for (String bad : new String[] {"", "   ", "{\"a\":1} trailing"}) {
      given()
          .contentType(ContentType.JSON)
          .body(new WorkspacePromptDraftController.SaveDraftRequest(bad, null))
          .when()
          .put(draftUrl(repoId, "master"))
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @Test
  public void oversizeSerializedPromptReturns413() {
    String repoId = createProjectAndRepository();
    // `content` is tiny and valid, but the co-stored serializedPrompt blows the 64-byte cap: the
    // combined payload is what's bounded, so serializedPrompt cannot bypass the guard.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "a".repeat(80)))
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
  }

  @Test
  public void absentRequestBodyReturns400() {
    String repoId = createProjectAndRepository();
    // No body at all: @NotNull on the parameter makes this a 400, not an NPE-driven 500.
    given()
        .contentType(ContentType.JSON)
        .when()
        .put(draftUrl(repoId, "master"))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void unknownRepositoryReturns404() {
    given()
        .when()
        .get(draftUrl("no-such-repo", "master"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void unknownWorkspaceReturns404() {
    String repoId = createProjectAndRepository();

    given()
        .when()
        .get(draftUrl(repoId, "no-such-workspace"))
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void discardingTheWorkspaceDeletesItsDraftLeavingNoOrphan() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "draft-ws", "master", "draft-branch");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "x"))
        .when()
        .put(draftUrl(repoId, "draft-ws"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // The workspace's Long id — the draft's PK — captured while the workspace is still active.
    Long workspaceId =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveByRepositoryAndWorkspaceId(repoId, "draft-ws")
                        .orElseThrow()
                        .id);
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> draftRepository.findByWorkspaceId(workspaceId).isPresent()),
        "draft should exist before discard");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/draft-ws/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Regression: the workspace is only soft-deleted, so the row cascade never fires — the draft
    // row must have been deleted explicitly, leaving no orphan.
    assertFalse(
        QuarkusTransaction.requiringNew()
            .call(() -> draftRepository.findByWorkspaceId(workspaceId).isPresent()),
        "draft row must be gone after discard");
  }

  @Test
  public void abandonmentOnMissingBranchDeletesDraftLeavingNoOrphan() throws Exception {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "gone-ws", "master", "gone-branch");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SaveDraftRequest("{\"v\":1}", "x"))
        .when()
        .put(draftUrl(repoId, "gone-ws"))
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    Long workspaceId =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveByRepositoryAndWorkspaceId(repoId, "gone-ws")
                        .orElseThrow()
                        .id);

    // Remove the durable branch ref out-of-band: on the next recreate the workspace has no branch
    // to materialize from, so ensure-container takes the second termination path (ABANDONED) —
    // which, like discard, must hard-delete the draft rather than orphan it.
    runGit(Path.of(dataDir, repoId, "origin"), "git", "branch", "-D", "gone-branch");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/gone-ws/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    awaitProcessDone(repoId, "gone-ws");

    assertFalse(
        QuarkusTransaction.requiringNew()
            .call(() -> draftRepository.findByWorkspaceId(workspaceId).isPresent()),
        "draft row must be gone after abandonment");
  }

  /**
   * Polls the workspace's active-process until it clears (the async provision/abandon finished).
   */
  private void awaitProcessDone(String repoId, String workspaceId) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      String processId =
          given()
              .when()
              .get("/api/repositories/" + repoId + "/workspaces/" + workspaceId + "/active-process")
              .then()
              .statusCode(Response.Status.OK.getStatusCode())
              .extract()
              .path("technicalProcessId");
      if (processId == null) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("workspace " + workspaceId + " process never completed");
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
}
