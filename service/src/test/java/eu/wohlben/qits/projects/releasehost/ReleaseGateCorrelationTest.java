package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
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
 * <b>The merged sha is the correlation key</b>, and this class is about what follows from that: a
 * verdict for a fold the request has moved past settles nothing, in either direction; and a re-fold
 * that supersedes a sha asks qits-ci to stop the runs that sha was being built by.
 *
 * <p>The two halves are one subject on purpose. The cancellation is <b>best effort</b> — it frees a
 * build agent and decides nothing — and the only reason that is affordable is the correlation
 * proved here: the gate is already safe when the cancellation never happens. A test that asserted
 * the cancellation alone would read as though the gate depended on it.
 *
 * <p>{@code ReleaseRequestFlowTest} owns the gate's ordinary rules and the state machine's timing;
 * what is asserted here is only what the sha correlates.
 */
@QuarkusTest
public class ReleaseGateCorrelationTest {

  @Inject eu.wohlben.qits.projects.bus.BuildStatusListener buildListener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject eu.wohlben.qits.projects.control.BuildStatusLedger ledger;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  @Inject RecordingQaRunCancellations cancellations;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    announcer.reset();
    cancellations.reset();
    // A run is always in flight here: every case in this class is about a request that must NOT
    // settle itself out from under the assertions once its settle window lapses.
    activeBuilds.answer(Optional.of(1));
    repoId = "gate-repo-" + UUID.randomUUID();
    projectId = "gate-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "gate";
              project.slug = "gate-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /** Open requests must not outlive this class: {@code sweep()} walks every open row. */
  @AfterEach
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
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

  private String mergedShaOf(String id) {
    return given().get(base() + "/" + id).then().extract().path("request.mergedSha");
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().extract().path("request.state");
  }

  private void headMoved(String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\"}",
            null,
            null,
            null));
  }

  private void verdict(String name, String sha, String extra) {
    buildListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\""
                + extra
                + "}",
            null,
            null,
            null));
  }

  // ---------------------------------------------------------------------------------------------
  // Only the current fold can settle a request
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aVerdictForASupersededFoldSettlesNothingInEitherDirection() {
    String id = create("work");
    String outrun = mergedShaOf(id);

    headMoved("work");
    String current = mergedShaOf(id);
    assertNotEquals(outrun, current, "the push produced a new fold");

    // The gate opens, so nothing but the correlation is holding the request back now.
    activeBuilds.answer(Optional.of(0));

    verdict("BuildSuccessful", outrun, "");
    assertEquals(
        "PENDING",
        stateOf(id),
        "a green verdict for the outrun fold must not release content nobody gated");

    verdict("BuildFailed", outrun, ",\"outcome\":\"FAILED\"");
    assertEquals(
        "PENDING",
        stateOf(id),
        "and a red one for it must not reject a request whose fold has already moved past it");

    // The ledger keeps the outrun fold's rows — they are the record of what was built — and the
    // request is settled by a verdict for the fold it actually gates.
    verdict("BuildFailed", current, ",\"outcome\":\"FAILED\"");
    assertEquals("REJECTED", stateOf(id));
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains(current), detail);
  }

  @Test
  public void theOutrunFoldsLedgerRowsAreKeptRatherThanDeleted() {
    String id = create("work");
    String outrun = mergedShaOf(id);
    verdict("BuildSuccessful", outrun, "");
    assertEquals(1, ledger.verdictsOf(repoId, outrun).size());

    headMoved("work");
    assertNotEquals(outrun, mergedShaOf(id));

    // NOTHING PRUNES THE LEDGER. The row is the record of what was built at that sha, and the gate
    // is safe without deleting it — a superseded fold's verdict simply matches no request. Deleting
    // would also be wrong the moment a fold produced that sha again, which `unchanged` does.
    assertEquals(
        1,
        ledger.verdictsOf(repoId, outrun).size(),
        "a superseded fold's verdict is ignored, not erased");
  }

  // ---------------------------------------------------------------------------------------------
  // Superseding a fold cancels its runs, and nobody else's
  // ---------------------------------------------------------------------------------------------

  @Test
  public void theFirstFoldCancelsNothingBecauseItSupersededNothing() {
    create("work");
    assertEquals(
        List.of(),
        cancellations.calls(),
        "a request's first fold replaced no sha, so no run exists to be stale");
  }

  @Test
  public void aRefoldCancelsTheSupersededRunsExactlyOnceAndNamesOnlyThisRequest() {
    String id = create("work");
    cancellations.reset();

    headMoved("work");

    assertEquals(1, cancellations.calls().size(), "one supersession, one cancellation");
    RecordingQaRunCancellations.Cancelled cancelled = cancellations.calls().get(0);
    assertEquals(repoId, cancelled.repoId());
    assertEquals(id, cancelled.releaseRequestId(), "scoped to the request whose fold moved");
  }

  @Test
  public void anUnchangedFoldSupersedesNothingAndCancelsNothing() {
    String id = create("work");
    String merged = mergedShaOf(id);
    cancellations.reset();

    // Every head already contained: same sha, no new commit. Nothing was re-armed, so whatever CI
    // is running is running for the sha the request still gates.
    merger.answer(eu.wohlben.qits.projects.control.BackingBranchMerger.Outcome.unchanged(merged));
    headMoved("work");

    assertEquals(List.of(), cancellations.calls(), "unchanged is not a supersession");
  }

  @Test
  public void aSharedTriggerCancelsPerRequestAndNeverASiblings() {
    String first = create("work-a");
    String second = create("work-b");
    cancellations.reset();

    // A push to main participates in every open request of the repository — and each of them folds
    // onto its OWN backing branch, so each supersedes its own sha and nobody else's.
    headMoved("main");

    assertEquals(
        List.of(first, second),
        cancellations.cancelledRequests().stream().sorted(comparingByCreation(first, second)).toList(),
        "two supersessions, two cancellations, each naming its own request");
    assertEquals(
        2, cancellations.calls().size(), "and no third call widening the scope to the repository");
    assertTrue(
        cancellations.calls().stream().allMatch(call -> call.repoId().equals(repoId)));
  }

  /** Order the two ids the way the fixture made them, so the assertion is about the SET. */
  private static java.util.Comparator<String> comparingByCreation(String first, String second) {
    return java.util.Comparator.comparingInt(id -> id.equals(first) ? 0 : 1);
  }

  private String awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(id);
      if (expected.equals(last)) {
        return last;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return last;
  }
}
