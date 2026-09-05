package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release-request state machine end to end: created over REST, folded onto its backing branch by
 * the recorded merger, settled by verdicts arriving through the real bus listener, executed against
 * the recorded door. The gate's individual rules are asserted here because this is the only place
 * they compose — the ledger write, the request resolution and the execution hand-off are one
 * consumption by design.
 *
 * <p><b>What a verdict is about is the MERGED sha</b>, not a branch head: the fold is what CI builds.
 * Every test therefore reads {@code mergedSha} off the request rather than choosing a sha, which is
 * also the assertion that the fold happened at all.
 *
 * <p><b>Every case here is settled by a verdict or by nothing at all</b>, because since 2026-09-04
 * there is nothing else that settles one: the settle window that used to pass an unvouched fold
 * vacuously is gone, and so is the property that configured it.
 */
@QuarkusTest
public class ReleaseRequestFlowTest {

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    announcer.reset();
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

  /**
   * <b>Open requests must not outlive this class</b>, the discipline {@code
   * ProjectReleaseRequestsTest} states and this class learned the hard way: {@code sweep()} walks
   * every open row in the database, so a request left PENDING by one test is a door call inside the
   * next test that sweeps — and the tests below count those calls.
   */
  @AfterEach
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              // The released tags this class's releases record are swept by the finalization belt,
              // which has no scope either — same discipline, one table over.
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a gated release\"}")
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

  private String mergedShaOf(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.mergedSha");
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
  public void aGreenGatingVerdictReleasesAndTheDoorIsAskedForTheFold() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    String merged = mergedShaOf(id);
    assertNotNull(merged, "the create folds the sources at once");
    assertEquals("PENDING", stateOf(id), "a run is still active, so the gate holds");

    activeBuilds.answer(Optional.of(0));
    verdict("BuildSuccessful", merged, "");
    awaitState(id, "RELEASED");

    assertEquals(1, executor.calls().size());
    RecordingReleaseExecutor.Released call = executor.calls().get(0);
    assertEquals(repoId, call.repoId());
    assertEquals(projectId, call.projectId());
    assertEquals("release/" + id, call.branch(), "what is released is the backing branch");
    assertEquals(merged, call.expectedSha(), "the door is pinned to the fold the gates evaluated");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.version", equalTo("2026.831.90000"));
  }

  @Test
  public void aRedGatingVerdictRejectsWithTheRunOnTheDetail() {
    String id = create("work");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"TIMED_OUT\"");
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
    String id = create("work");
    String merged = mergedShaOf(id);

    verdict("BuildFailed", merged, ",\"outcome\":\"FAILED\",\"gating\":false");
    assertEquals("PENDING", stateOf(id), "a non-gating failure is read and ignored");

    verdict("BuildSuccessful", merged, "");
    awaitState(id, "RELEASED");
  }

  /**
   * <b>The gate gates.</b> A fold nothing has vouched for does not pass — not on the first
   * evaluation, not on the tenth sweep, not after any wait, and pointedly not because qits-ci says
   * it has no runs in flight for the commit.
   *
   * <p>That last clause is the whole of the 2026-09-04 fix. Until then a sha with no verdict passed
   * <em>vacuously</em> once a settle window lapsed, and the window's own justification was that an
   * accepted run would show as active by the time it ended. QA runs are created over the event bus
   * and executed by one serial runner, so the probe answered 0 while the run was still being
   * accepted, and releases went PENDING → RELEASED in under two minutes with their QA runs still
   * queued behind them. A verdict is the only key to this door now, and a repository whose pipeline
   * never materializes simply cannot release.
   */
  @Test
  public void aFoldNothingVouchesForNeverPassesHoweverLongItWaits() {
    activeBuilds.answer(Optional.of(0));
    String id = create("work");
    assertEquals("PENDING", stateOf(id), "no verdict, no release");

    // Well past the window that used to pass it (PT2S in the test properties, when there was one).
    try {
      Thread.sleep(2_500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    releaseRequests.sweep();
    releaseRequests.sweep();
    releaseRequests.sweep();

    assertEquals("PENDING", stateOf(id), "an idle CI is not a verdict and never becomes one");
    assertEquals(0, executor.calls().size(), "and nothing was released on nobody's word");
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("Waiting for a gating CI verdict"), detail);

    // And the one thing that does open it, on the very same request.
    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");
  }

  /**
   * A green verdict for a fold this request has moved past is not the vouch it needs: the gate is
   * correlated by merged sha in both directions, and a superseded sha's ledger row settles nothing.
   * With the vacuous pass gone, the request simply stays PENDING for ever on such a verdict.
   */
  @Test
  public void aGreenVerdictForASupersededFoldIsIgnoredForEver() {
    activeBuilds.answer(Optional.of(0));
    String id = create("work");
    String superseded = mergedShaOf(id);

    headMoved("work", sha());
    String current = mergedShaOf(id);
    assertTrue(!current.equals(superseded), "the push re-folded the request");

    verdict("BuildSuccessful", superseded, "");
    releaseRequests.sweep();
    releaseRequests.sweep();
    assertEquals("PENDING", stateOf(id), "the vouch is for content nobody will accept any more");
    assertEquals(0, executor.calls().size());

    verdict("BuildSuccessful", current, "");
    awaitState(id, "RELEASED");
    assertEquals(current, executor.calls().get(0).expectedSha());
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
  public void oneOpenRequestPerBranchAndAskingAgainConvergesOnIt() {
    activeBuilds.answer(Optional.of(1));
    String first = create("work");
    String second = create("work");

    // The merge-request shape: the branch participates in ONE open request, and asking again
    // answers it rather than opening a second.
    assertEquals(first, second);
    given().get(base() + "/" + first).then().body("request.state", equalTo("PENDING"));
  }

  @Test
  public void aPushToAParticipatingBranchRefoldsAndRearmsOntoTheNewMerge() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    String gated = mergedShaOf(id);

    headMoved("work", sha());
    String refolded = mergedShaOf(id);
    assertTrue(!refolded.equals(gated), "a push to a source re-folds the request");
    given().get(base() + "/" + id).then().body("request.state", equalTo("PENDING"));

    // The old fold's verdict is now about a sha the request no longer gates — it settles nothing.
    activeBuilds.answer(Optional.of(0));
    verdict("BuildSuccessful", gated, "");
    assertEquals("PENDING", stateOf(id), "a verdict for the outrun fold must not release the new one");

    verdict("BuildSuccessful", refolded, "");
    awaitState(id, "RELEASED");
    assertEquals(refolded, executor.calls().get(0).expectedSha(), "what lands is what was re-gated");
  }

  @Test
  public void aPushToMainRefoldsEveryOpenRequestOfTheRepositoryOnItsOwnBranch() {
    activeBuilds.answer(Optional.of(1));
    String first = create("work-a");
    String second = create("work-b");
    merger.reset();

    headMoved("main", sha());

    // A shared trigger, two folds, each onto its OWN backing branch: one request's re-merge never
    // touches a sibling's.
    assertEquals(1, merger.foldsOf("refs/heads/release/" + first).size());
    assertEquals(1, merger.foldsOf("refs/heads/release/" + second).size());
    assertEquals(2, merger.folds().size(), "and nothing else was folded");
  }

  @Test
  public void aRejectedRequestComesBackToLifeWhenTheFixLands() {
    String id = create("work");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    headMoved("work", sha());
    assertEquals("PENDING", stateOf(id), "the fix a rejection asks for is exactly what a push is");

    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");
  }

  @Test
  public void aRetryableRefusalIsFailedWithTheDoorsWordsAndTheSweepRetriesIt() {
    executor.answer(ReleaseExecutor.Outcome.refusedRetryable("the door could not be reached"));
    String id = create("work");
    String merged = mergedShaOf(id);
    verdict("BuildSuccessful", merged, "");
    awaitState(id, "FAILED");
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be reached"), detail);

    // The sweep re-folds nothing (the fold is already made), so the retry is about the door alone.
    executor.answer(ReleaseExecutor.Outcome.released("2026.831.90001", "released-sha-1"));
    releaseRequests.sweep();
    awaitState(id, "RELEASED");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.version", equalTo("2026.831.90001"));
  }

  @Test
  public void aFinalRefusalStandsUntilAPushChangesTheAsk() {
    // The unbounded-loop fix: ALREADY_INTEGRATED-shaped answers repeat forever, so the sweep must
    // leave them standing — and the re-arm is what revives them, because it changes the ask.
    executor.answer(ReleaseExecutor.Outcome.refused("409: ALREADY_INTEGRATED"));
    String id = create("work");
    String merged = mergedShaOf(id);
    verdict("BuildSuccessful", merged, "");
    awaitState(id, "FAILED");
    assertEquals(1, executor.calls().size());

    executor.answer(ReleaseExecutor.Outcome.released("2026.831.90002", "released-sha-2"));
    releaseRequests.sweep();
    releaseRequests.sweep();
    // The worker is asynchronous: give a wrongly-enqueued execution time to show up as a call.
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertEquals("FAILED", stateOf(id), "a final refusal is not knocked on again");
    assertEquals(1, executor.calls().size(), "the sweep made no further door call");

    headMoved("work", sha());
    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");
  }

  private void branchDeleted(String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMDeleteBranch",
            Instant.now(),
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\"" + sha()
                + "\"}",
            null,
            null,
            null));
  }

  @Test
  public void aDeletedSourceLeavingNothingButMainWithdrawsTheRequest() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");

    branchDeleted("work");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.state", equalTo("WITHDRAWN"))
        .body("request.detail", equalTo("Withdrawn: the branch was deleted"));

    // WITHDRAWN is not open: a moving head revives nothing, and a new ask mints a fresh row.
    headMoved("work", sha());
    assertEquals("WITHDRAWN", stateOf(id));
    String fresh = create("work");
    assertTrue(!fresh.equals(id), "a withdrawn request is never converged on");
  }

  @Test
  public void anOperatorWithdrawsAMootRequestAndTerminalOnesRefuse() {
    executor.answer(ReleaseExecutor.Outcome.refused("409: ALREADY_INTEGRATED"));
    String id = create("work");
    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "FAILED");

    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"already integrated; the work shipped through another door\"}")
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(200)
        .body("request.state", equalTo("WITHDRAWN"))
        .body(
            "request.detail",
            equalTo("already integrated; the work shipped through another door"));

    // Withdrawing what already concluded would rewrite a record.
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(409);
  }

  // -----------------------------------------------------------------------------------------------
  // The repository's own list
  // -----------------------------------------------------------------------------------------------

  /**
   * <b>The repository list takes the same state vocabulary the project-wide one does</b>, and the
   * default is the open work plus the last ten releases rather than everything ever asked for.
   *
   * <p>That is a deliberate loss and it is worth naming: a WITHDRAWN request used to appear here,
   * because this route had no filter at all rather than because anything decided it belonged. It is
   * one query parameter away in either spelling.
   */
  @Test
  public void theDefaultDropsAWithdrawnRequestAndEitherFilterFindsItAgain() {
    activeBuilds.answer(Optional.of(1));
    String id = create("moot");
    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"moot\"}")
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(200);

    assertTrue(!idsAt("").contains(id), "withdrawn is not open work and is not a recent release");
    assertTrue(idsAt("?state=all").contains(id), "the whole history still holds it");
    assertTrue(idsAt("?state=WITHDRAWN").contains(id), "and so does its own state");

    // A typo must never read as "nothing has been asked for here" — the project route's posture,
    // now on this one too.
    given()
        .get(base() + "?state=widthrawn")
        .then()
        .statusCode(400)
        .body("message", containsString("widthrawn"))
        .body("message", containsString("WITHDRAWN"));
  }

  /**
   * <b>What the tag points at, on the request that made it.</b> {@code releasedSha} is not {@code
   * mergedSha}: the release commits the rewritten manifests onto the fold and tags <em>that</em>
   * commit, so the two are a parent and its child — and the sha a reader needs to open the release
   * in the code browser is the second one. It rides on the single read and on the list alike, out of
   * the batch read that already fetched the released tag for {@code mergedToMainAt}.
   */
  @Test
  public void aReleasedRequestSaysWhatItsTagPointsAt() {
    activeBuilds.answer(Optional.of(0));
    String id = create("work");
    String merged = mergedShaOf(id);
    verdict("BuildSuccessful", merged, "");
    awaitState(id, "RELEASED");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.version", equalTo("2026.831.90000"))
        .body("request.releasedSha", equalTo("released-sha-0"))
        .body("request.mergedSha", equalTo(merged));

    given()
        .get(base())
        .then()
        .statusCode(200)
        .body("requests.find { it.id == '" + id + "' }.releasedSha", equalTo("released-sha-0"));
  }

  /** A request that has released nothing has no tag, so both of the tag's fields are null. */
  @Test
  public void anUnreleasedRequestNamesNoReleasedSha() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.releasedSha", nullValue())
        .body("request.mergedToMainAt", nullValue());
  }

  private List<String> idsAt(String query) {
    return given().get(base() + query).then().statusCode(200).extract().path("requests.id");
  }
}
