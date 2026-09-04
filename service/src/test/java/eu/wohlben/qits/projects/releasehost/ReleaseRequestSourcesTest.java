package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
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
 * The N-source half of a release request: what it is composed of, what re-folds it, and what the
 * platform is told when a fold lands.
 *
 * <p>The gate and the execution are {@code ReleaseRequestFlowTest}'s subject; this class holds the
 * gate open ({@link FakeActiveBuilds} answering "a run is still active") wherever a release would
 * otherwise settle a fixture mid-test, so that what is asserted is the composition and not the
 * state machine's timing.
 */
@QuarkusTest
public class ReleaseRequestSourcesTest {

  @Inject ReleaseRequests releaseRequests;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject eu.wohlben.qits.projects.bus.BuildStatusListener buildListener;

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
    activeBuilds.answer(Optional.of(1));
    repoId = "sources-repo-" + UUID.randomUUID();
    projectId = "sources-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "sources";
              project.slug = "sources-" + UUID.randomUUID();
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
   * ProjectReleaseRequestsTest} states: {@code sweep()} walks every open row in the database, so a
   * fixture left PENDING here is a door call inside the next test that sweeps.
   */
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
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\""
                + RecordingBackingBranchMerger.freshSha() + "\"}",
            null,
            null,
            null));
  }

  private void greenVerdict(String sha) {
    buildListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"commitSha\":\"" + sha + "\",\"repoId\":\"" + repoId + "\",\"runId\":\"run-"
                + UUID.randomUUID() + "\"}",
            null,
            null,
            null));
  }

  /**
   * The sibling re-folds are made on the release worker, after the released request settles — so a
   * fold is polled for, never assumed to have happened by the time RELEASED is visible.
   */
  private List<RecordingBackingBranchMerger.Fold> awaitFoldContaining(String target, String ref) {
    long deadline = System.currentTimeMillis() + 10_000;
    List<RecordingBackingBranchMerger.Fold> folds = List.of();
    while (System.currentTimeMillis() < deadline) {
      folds = merger.foldsOf(target);
      if (!folds.isEmpty() && folds.get(folds.size() - 1).sources().contains(ref)) {
        return folds;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    return fail(
        target + " was never folded with " + ref + "; last sources " + folds);
  }

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

  // -------------------------------------------------------------------------------------------
  // The model
  // -------------------------------------------------------------------------------------------

  @Test
  public void aCreateNamesOneBranchAndImpliesMain() {
    String id = create("work");

    given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .body("request.backingBranch", equalTo("release/" + id))
        .body("request.sources.name", contains("main", "work"))
        .body("request.sources.ref", contains("refs/heads/main", "refs/heads/work"))
        .body("request.sources.implicit", contains(false, false))
        .body("request.conflict", equalTo(null));

    // main first, because the order sources are added in is the order they become parents.
    assertEquals(
        List.of("refs/heads/main", "refs/heads/work"),
        merger.foldsOf("refs/heads/release/" + id).get(0).sources());
  }

  @Test
  public void namingTheDefaultBranchMakesAMainOnlyRequest() {
    String id = create("main");

    given().get(base() + "/" + id).then().body("request.sources.name", contains("main"));
    assertEquals(
        List.of("refs/heads/main"),
        merger.foldsOf("refs/heads/release/" + id).get(0).sources());
  }

  @Test
  public void aSourceIsAddedToAnOpenRequestAndTheFoldIsRedone() {
    String id = create("work");
    String first = mergedShaOf(id);

    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"work-two\"}")
        .post(base() + "/" + id + "/sources")
        .then()
        .statusCode(200)
        .body("request.sources.name", contains("main", "work", "work-two"));

    assertEquals(
        List.of("refs/heads/main", "refs/heads/work", "refs/heads/work-two"),
        merger.foldsOf("refs/heads/release/" + id).get(1).sources());
    assertNotEquals(first, mergedShaOf(id), "a new source is new content, so a new fold");
  }

  @Test
  public void addingTheSameSourceTwiceIsAddingItOnceAndFoldsNothing() {
    String id = create("work");
    int foldsAfterCreate = merger.foldsOf("refs/heads/release/" + id).size();

    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"work\"}")
        .post(base() + "/" + id + "/sources")
        .then()
        .statusCode(200)
        .body("request.sources.name", contains("main", "work"));

    assertEquals(
        foldsAfterCreate,
        merger.foldsOf("refs/heads/release/" + id).size(),
        "an idempotent add asks the git host for nothing");
  }

  @Test
  public void aSettledRequestTakesNoMoreSources() {
    String id = create("work");
    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"moot\"}")
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(200);

    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"late\"}")
        .post(base() + "/" + id + "/sources")
        .then()
        .statusCode(409);
  }

  @Test
  public void aReleaseStillInFlightIsReportedAsAnImplicitSourceAndCannotBeManaged() {
    QuarkusTransaction.requiringNew().run(() -> pendingTag("2026.901.120000"));
    String id = create("work");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.sources.name", contains("main", "work", "2026.901.120000"))
        .body("request.sources.ref", hasItem("refs/tags/2026.901.120000"))
        .body("request.sources.findAll { it.implicit }.kind", contains("RELEASED_TAG"));

    // And it really is folded in: the fold is a superset of the release already in flight.
    assertTrue(
        merger
            .foldsOf("refs/heads/release/" + id)
            .get(0)
            .sources()
            .contains("refs/tags/2026.901.120000"));
  }

  @Test
  public void aTagAlreadyMergedToMainIsNotAnImplicitSource() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasedTagPendingMerge merged = pendingTag("2026.901.100000");
              merged.mergedAt = Instant.now();
            });
    String id = create("work");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.sources.name", not(hasItem("2026.901.100000")))
        .body("request.sources", hasSize(2));
  }

  private ReleasedTagPendingMerge pendingTag(String tag) {
    ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
    row.id = UUID.randomUUID().toString();
    row.repoId = repoId;
    row.tagName = tag;
    row.releasedSha = RecordingBackingBranchMerger.freshSha();
    row.releasedAt = Instant.now();
    row.persist();
    return row;
  }

  // -------------------------------------------------------------------------------------------
  // The dispatch
  // -------------------------------------------------------------------------------------------

  @Test
  public void aLandedFoldAnnouncesTheRequestChangedWithEverythingAConsumerNeeds() {
    String id = create("work");

    List<RecordingReleaseRequestAnnouncer.Announced> events = announcer.announcedFor(id);
    assertEquals(1, events.size(), "the create's fold is a change and is announced once");
    RecordingReleaseRequestAnnouncer.Announced event = events.get(0);
    assertEquals(projectId, event.projectId());
    assertEquals(repoId, event.repoId());
    assertEquals(id, event.releaseRequestId());
    assertEquals("release/" + id, event.backingBranch());
    assertEquals(mergedShaOf(id), event.mergedSha(), "what a consumer builds is the fold's tip");

    announcer.reset();
    headMoved("work");
    assertEquals(1, announcer.announcedFor(id).size(), "and again when a push re-folds it");
  }

  @Test
  public void anUnchangedFoldIsNotAChangeAndAnnouncesNothing() {
    String id = create("work");
    String merged = mergedShaOf(id);
    announcer.reset();

    // Every head already contained: same sha, no new commit. This is what a trigger with no
    // content behind it produces, and announcing it would ask for a build of a sha already built.
    merger.answer(BackingBranchMerger.Outcome.unchanged(merged));
    headMoved("work");

    assertEquals(
        2, merger.foldsOf("refs/heads/release/" + id).size(), "the fold was attempted all the same");
    assertEquals(List.of(), announcer.announcedFor(id), "unchanged is not a change");
    assertEquals(merged, mergedShaOf(id), "and the request still points at the same fold");
  }

  @Test
  public void anUnreachableGitHostLeavesTheRequestStandingAndTheSweepFoldsAgain() {
    merger.answer(BackingBranchMerger.Outcome.unreachable("qits-githost could not be reached"));
    String id = create("work");

    assertNull(mergedShaOf(id), "nothing was folded, so there is nothing to gate");
    assertEquals("PENDING", stateOf(id));
    assertEquals(List.of(), announcer.announcedFor(id));

    merger.answerFreshMerges();
    releaseRequests.sweep();
    assertTrue(mergedShaOf(id) != null, "the sweep folds a request that never got its first fold");
    assertEquals(1, announcer.announcedFor(id).size());
  }

  // -------------------------------------------------------------------------------------------
  // Conflicts
  // -------------------------------------------------------------------------------------------

  @Test
  public void aConflictFlipsTheRequestToConflictedWithTheDetailAndAnnouncesNothing() {
    String id = create("work");
    String merged = mergedShaOf(id);
    announcer.reset();

    merger.answer(
        BackingBranchMerger.Outcome.conflict(
            "refs/heads/release/" + id,
            List.of(
                new BackingBranchMerger.Conflict(
                    "pom.xml", "refs/heads/work", "abc1234", "content"))));
    headMoved("work");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.state", equalTo("CONFLICTED"))
        .body("request.conflict.target", equalTo("refs/heads/release/" + id))
        .body("request.conflict.conflicts[0].path", equalTo("pom.xml"))
        .body("request.conflict.conflicts[0].head", equalTo("refs/heads/work"))
        .body("request.conflict.conflicts[0].headSha", equalTo("abc1234"))
        .body("request.conflict.conflicts[0].reason", equalTo("content"))
        .body("request.mergedSha", equalTo(merged));
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("pom.xml"), detail);
    assertEquals(List.of(), announcer.announcedFor(id), "no ref moved, so there is nothing to build");
  }

  @Test
  public void aConflictedRequestIsNotKnockedOnBySweepsAndIsClearedByTheNextCleanFold() {
    String id = create("work");
    merger.answer(
        BackingBranchMerger.Outcome.conflict(
            "refs/heads/release/" + id,
            List.of(
                new BackingBranchMerger.Conflict(
                    "pom.xml", "refs/heads/work", "abc1234", "content"))));
    headMoved("work");
    assertEquals("CONFLICTED", stateOf(id));
    int foldsWhenConflicted = merger.foldsOf("refs/heads/release/" + id).size();

    // A conflict answers the same on every knock: the sweep must not re-fold it, which is the
    // unbounded-loop defect this aggregate already paid for once.
    releaseRequests.sweep();
    releaseRequests.sweep();
    assertEquals(foldsWhenConflicted, merger.foldsOf("refs/heads/release/" + id).size());

    // Something changed: the push that resolves it clears the state, the detail and the event.
    announcer.reset();
    merger.answerFreshMerges();
    headMoved("work");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.state", equalTo("PENDING"))
        .body("request.conflict", equalTo(null));
    assertEquals(1, announcer.announcedFor(id).size(), "the clean fold is a change like any other");
  }

  /**
   * The git host's 409. The target it names is the outcome's own word for it; what the request
   * stores is the ref the domain computed, so this one needs no request id and can be staged before
   * the create that hits it.
   */
  private static BackingBranchMerger.Outcome conflictingFold() {
    return BackingBranchMerger.Outcome.conflict(
        "refs/heads/release",
        List.of(new BackingBranchMerger.Conflict("pom.xml", "refs/heads/work", "abc1234", "content")));
  }

  @Test
  public void aConflictOnCreationIsClearedByAnUnchangedFoldThatStillDispatches() {
    merger.answer(conflictingFold());
    String id = create("work");
    assertEquals("CONFLICTED", stateOf(id));
    assertNull(mergedShaOf(id), "no ref moved, so this request has never had a fold to build");
    assertEquals(List.of(), announcer.announcedFor(id));

    // The fix resolves to the branch's existing tip: a real change to what was asked for, and the
    // same sha all the same. NOTHING has ever built that sha — this request announced nothing when
    // it was created — so with no event here no run is ever created and, since the vacuous pass
    // went, the gate waits for ever. Measured live on request 7247b350, 2026-09-04.
    String tip = RecordingBackingBranchMerger.freshSha();
    merger.answer(BackingBranchMerger.Outcome.unchanged(tip));
    headMoved("work");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.state", equalTo("PENDING"))
        .body("request.conflict", equalTo(null))
        .body("request.mergedSha", equalTo(tip));
    assertEquals(1, announcer.announcedFor(id).size(), "clearing a conflict dispatches");
    assertEquals(tip, announcer.announcedFor(id).get(0).mergedSha(), "onto the sha to be built");
  }

  @Test
  public void aConflictClearedOntoAShaAVerdictAlreadyCoversAnnouncesNothing() {
    String id = create("work");
    String merged = mergedShaOf(id);
    greenVerdict(merged);
    assertEquals("PENDING", stateOf(id), "a run is still in flight, so the vouch is held");
    merger.answer(conflictingFold());
    headMoved("work");
    assertEquals("CONFLICTED", stateOf(id));
    announcer.reset();

    // Back onto the sha a gating run has already answered for. The gate reads that verdict on the
    // way out of the fold, so an event here would only ask for a second build of built content.
    merger.answer(BackingBranchMerger.Outcome.unchanged(merged));
    headMoved("work");
    assertEquals(
        List.of(), announcer.announcedFor(id), "the sha carries a verdict the gate can read");

    // And the request is not stuck: what clears it is the verdict that was there all along.
    activeBuilds.answer(Optional.of(0));
    releaseRequests.sweep();
    awaitState(id, "RELEASED");
    assertEquals(merged, executor.calls().get(0).expectedSha());
  }

  // -------------------------------------------------------------------------------------------
  // The implicit set moving
  // -------------------------------------------------------------------------------------------

  @Test
  public void aSiblingReleaseJoinsTheImplicitSetAndRefoldsEveryOtherOpenRequest() {
    String shipping = create("work-shipping");
    String waiting = create("work-waiting");

    // Let the first one release. Its calver becomes a tag that has not reached main yet.
    activeBuilds.answer(Optional.of(0));
    greenVerdict(mergedShaOf(shipping));
    awaitState(shipping, "RELEASED");
    // And the gate closes again, so the waiting request cannot settle itself out from under the
    // assertions below once its own settle window lapses.
    activeBuilds.answer(Optional.of(1));

    // The waiting request is now a superset of the release in flight: the tag joined its sources
    // and it was re-folded on its own backing branch.
    awaitFoldContaining("refs/heads/release/" + waiting, "refs/tags/2026.831.90000");
    given()
        .get(base() + "/" + waiting)
        .then()
        .body("request.sources.name", hasItem("2026.831.90000"))
        .body("request.sources.findAll { it.implicit }.kind", contains("RELEASED_TAG"));

    // And the released one is not re-folded by its own release.
    assertEquals(
        1,
        merger.foldsOf("refs/heads/release/" + shipping).size(),
        "a settled request is not re-folded");
  }

  @Test
  public void aPendingTagReachingMainLeavesTheSetAndIsContentIdempotent() {
    String shipping = create("work-shipping");
    String waiting = create("work-waiting");
    activeBuilds.answer(Optional.of(0));
    greenVerdict(mergedShaOf(shipping));
    awaitState(shipping, "RELEASED");
    activeBuilds.answer(Optional.of(1));

    int foldsBefore =
        awaitFoldContaining("refs/heads/release/" + waiting, "refs/tags/2026.831.90000").size();
    String foldedWith = mergedShaOf(waiting);
    announcer.reset();

    // The post-deployment merge puts the tag on main. It is already contained in the fold through
    // main itself, so the re-fold answers `unchanged` — a real trigger with no content behind it.
    merger.answer(BackingBranchMerger.Outcome.unchanged(foldedWith));
    releaseRequests.onReleasedTagMerged(repoId, "2026.831.90000");

    assertEquals(
        foldsBefore + 1,
        merger.foldsOf("refs/heads/release/" + waiting).size(),
        "leaving the set is a real trigger and the request is re-folded");
    assertTrue(
        !merger.lastFold().sources().contains("refs/tags/2026.831.90000"),
        "and the tag is gone from what is folded: " + merger.lastFold().sources());
    assertEquals(
        List.of(), announcer.announcedFor(waiting), "unchanged content dispatches no event");
    given()
        .get(base() + "/" + waiting)
        .then()
        .body("request.sources.name", not(hasItem("2026.831.90000")));

    // Clearing a tag nothing has a pending row for is a no-op, not a fold.
    int settled = merger.folds().size();
    releaseRequests.onReleasedTagMerged(repoId, "2026.831.90000");
    assertEquals(settled, merger.folds().size(), "a tag already merged is cleared once");
  }
}
