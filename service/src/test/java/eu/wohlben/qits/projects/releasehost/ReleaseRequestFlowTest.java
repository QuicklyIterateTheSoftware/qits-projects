package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release-request state machine end to end: created over REST, settled by verdicts arriving
 * through the real bus listener, executed against the recorded door. The gate's individual rules
 * are asserted here because this is the only place they compose — the ledger write, the request
 * resolution and the execution hand-off are one consumption by design.
 *
 * <p>{@code qits.projects.release-requests.settle} is shortened to {@code PT2S} in the test
 * properties, so the vacuous arm is a two-second wait rather than thirty; every other case is
 * settled by a verdict and never touches the window.
 */
@QuarkusTest
public class ReleaseRequestFlowTest {

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    repoId = "release-repo-" + UUID.randomUUID();
    projectId = "release-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "release-flow";
              project.slug = "release-flow-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch, String sha) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"branch\":\"" + branch + "\",\"commitSha\":\"" + sha
                + "\",\"summary\":\"a gated release\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private void verdict(String name, String sha, String extra) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\"" + sha + "\",\"repoId\":\"" + repoId
                + "\",\"runId\":\"run-" + UUID.randomUUID() + "\"" + extra + "}",
            null,
            null,
            null));
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.state");
  }

  /** The execution runs on the request worker, so a terminal state is polled, never assumed. */
  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(id);
      if (expected.equals(last)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " never reached " + expected + "; last seen " + last);
  }

  private static String sha() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  public void aGreenGatingVerdictReleasesAndTheDoorIsAskedAsThePersonWhoRequested() {
    String sha = sha();
    activeBuilds.answer(Optional.of(1));
    String id = create("work", sha);
    assertEquals("PENDING", stateOf(id), "a run is still active, so the gate holds");

    activeBuilds.answer(Optional.of(0));
    verdict("BuildSuccessful", sha, "");
    awaitState(id, "RELEASED");

    assertEquals(1, executor.calls().size());
    RecordingReleaseExecutor.Released call = executor.calls().get(0);
    assertEquals(repoId, call.repoId());
    assertEquals(projectId, call.projectId());
    assertEquals("work", call.branch());
    assertEquals(sha, call.expectedSha(), "the door is pinned to the sha the gates evaluated");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.version", equalTo("2026.831.90000"));
  }

  @Test
  public void aRedGatingVerdictRejectsWithTheRunOnTheDetail() {
    String sha = sha();
    String id = create("work", sha);

    verdict("BuildFailed", sha, ",\"outcome\":\"TIMED_OUT\"");
    awaitState(id, "REJECTED");

    String detail =
        given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("TIMED_OUT"), detail);
    assertEquals(0, executor.calls().size(), "a rejected request must never reach the door");
  }

  @Test
  public void aRedNonGatingVerdictNeverBlocksTheGreenOne() {
    // The userflows case, the reason the flag exists: a red story delays nothing and blocks
    // nothing once a gating run has vouched for the commit.
    String sha = sha();
    String id = create("work", sha);

    verdict("BuildFailed", sha, ",\"outcome\":\"FAILED\",\"gating\":false");
    assertEquals("PENDING", stateOf(id), "a non-gating failure is read and ignored");

    verdict("BuildSuccessful", sha, "");
    awaitState(id, "RELEASED");
  }

  @Test
  public void aShaNothingVouchesForPassesOnlyAfterTheSettleWindow() {
    String sha = sha();
    String id = create("work", sha);
    assertEquals("PENDING", stateOf(id), "inside the settle window nothing passes vacuously");

    // Still pending after an early sweep: the window is the floor, not a formality.
    releaseRequests.sweep();
    assertEquals("PENDING", stateOf(id));

    try {
      Thread.sleep(2_300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    releaseRequests.sweep();
    awaitState(id, "RELEASED");
    assertEquals(1, executor.calls().size(), "the vacuous pass reaches the door exactly once");
  }

  private void headMoved(String branch, String sha) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\"" + sha
                + "\"}",
            null,
            null,
            null));
  }

  @Test
  public void oneOpenRequestPerBranchAndANewShaRearmsIt() {
    activeBuilds.answer(Optional.of(1));
    String first = create("work", sha());
    String secondSha = sha();
    String second = create("work", secondSha);

    // The merge-request shape: the branch has ONE open request, and a new sha re-arms it.
    assertEquals(first, second);
    given()
        .get(base() + "/" + first)
        .then()
        .body("request.state", equalTo("PENDING"))
        .body("request.commitSha", equalTo(secondSha));
  }

  @Test
  public void aPushMovingTheBranchRearmsTheGatesOntoTheNewHead() {
    String gated = sha();
    activeBuilds.answer(Optional.of(1));
    String id = create("work", gated);

    String newHead = sha();
    headMoved("work", newHead);
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.state", equalTo("PENDING"))
        .body("request.commitSha", equalTo(newHead));

    // The old head's verdict is now about a sha the request no longer gates — it settles nothing.
    activeBuilds.answer(Optional.of(0));
    verdict("BuildSuccessful", gated, "");
    assertEquals("PENDING", stateOf(id), "a verdict for the outrun sha must not release the new one");

    verdict("BuildSuccessful", newHead, "");
    awaitState(id, "RELEASED");
    assertEquals(newHead, executor.calls().get(0).expectedSha(), "what lands is what was re-gated");
  }

  @Test
  public void aRejectedRequestComesBackToLifeWhenTheFixLands() {
    String red = sha();
    String id = create("work", red);
    verdict("BuildFailed", red, ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    String fixed = sha();
    headMoved("work", fixed);
    assertEquals("PENDING", stateOf(id), "the fix a rejection asks for is exactly what a push is");

    verdict("BuildSuccessful", fixed, "");
    awaitState(id, "RELEASED");
  }

  @Test
  public void aRefusedExecutionIsFailedWithTheDoorsWordsAndTheSweepRetriesIt() {
    String sha = sha();
    executor.answer(ReleaseExecutor.Outcome.refused("the door said no"));
    String id = create("work", sha);
    verdict("BuildSuccessful", sha, "");
    awaitState(id, "FAILED");
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("the door said no"), detail);

    executor.answer(ReleaseExecutor.Outcome.released("2026.831.90001"));
    releaseRequests.sweep();
    awaitState(id, "RELEASED");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.version", equalTo("2026.831.90001"));
  }
}
