package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
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
 * <b>The publish phase: a deployment goes active and the release it deployed reaches {@code
 * main}.</b> Driven through the durable listener that hears it, against a git host that records
 * what it was asked to fold.
 *
 * <p>What is asserted here is the half nothing else can see: {@code main} is the one ref this
 * platform advances by merging rather than by pushing, so "what did the git host get asked for" is
 * the whole contract — the target, the source, and that a second telling asks for nothing.
 */
@QuarkusTest
public class MainFinalizationTest {

  @Inject eu.wohlben.qits.projects.bus.DeploymentActiveListener deployments;

  @Inject ReleaseFinalization finalization;

  @Inject RecordingBackingBranchMerger merger;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    announcer.reset();
    // A run is still active, so no fixture request settles itself mid-test: what is under test is
    // the merge to main, never the gate's timing.
    activeBuilds.answer(Optional.of(1));
    repoId = "publish-repo-" + UUID.randomUUID();
    projectId = "publish-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "publish";
              project.slug = "publish-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /**
   * Nothing of this fixture may outlive the class: both sweeps walk every open request and every
   * owed merge in the database, so a row left behind is a git-host call inside somebody else's test.
   */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  // -----------------------------------------------------------------------------------------------
  // The merge
  // -----------------------------------------------------------------------------------------------

  @Test
  public void aDeploymentGoingActiveMergesThatReleasedTagIntoMain() {
    String tag = freshTag();
    String releasedSha = QuarkusTransaction.requiringNew().call(() -> pendingTag(tag).releasedSha);

    deploymentActive("publisher", tag, "dev");

    List<RecordingBackingBranchMerger.Fold> intoMain = merger.foldsOf("refs/heads/main");
    assertEquals(1, intoMain.size(), "one deployment, one merge into main");
    assertEquals(repoId, intoMain.get(0).repoId());
    assertEquals(
        List.of(releasedSha),
        intoMain.get(0).sources(),
        "the recorded released SHA, never the tag ref: the branches the release consumed were"
            + " deleted when it landed and a ref can be moved");
    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.mergedAt, "the tag reached main, which is what merged_at records");
    assertNotNull(row.mergeRequestedAt, "and the gate it passed to get there");
    assertNull(row.mergeDetail, "nothing failed, so the row carries no reason");
  }

  /**
   * The other half of the same call: a tag on {@code main} leaves the repository's implicit source
   * set, so every open request folds again without it.
   */
  @Test
  public void theOpenRequestsReFoldWithoutTheTagThatReachedMain() {
    String tag = freshTag();
    QuarkusTransaction.requiringNew().run(() -> pendingTag(tag));
    String id = create("work");
    String backing = "refs/heads/release/" + id;
    assertTrue(
        merger.foldsOf(backing).get(0).sources().contains("refs/tags/" + tag),
        "while the tag is in flight, the request folds it in — that is the premise");
    int before = merger.foldsOf(backing).size();

    deploymentActive("publisher", tag, "dev");

    List<RecordingBackingBranchMerger.Fold> after = merger.foldsOf(backing);
    assertEquals(before + 1, after.size(), "the tag leaving the set re-folds every open request");
    assertFalse(
        after.get(after.size() - 1).sources().contains("refs/tags/" + tag),
        "and it is not a source any more: main contains it now");
  }

  @Test
  public void aReplayedDeploymentMergesNothingASecondTime() {
    String tag = freshTag();
    QuarkusTransaction.requiringNew().run(() -> pendingTag(tag));

    deploymentActive("publisher", tag, "dev");
    deploymentActive("publisher", tag, "dev");
    deploymentActive("publisher", tag, "prod");
    finalization.sweep();

    assertEquals(
        1,
        merger.foldsOf("refs/heads/main").size(),
        "merged_at short-circuits before any call is made, so a duplicate delivery, a"
            + " re-deployment and a sweep are all free");
  }

  @Test
  public void aVersionNothingHereReleasedIsSettledWithoutAskingTheGitHost() {
    deploymentActive("somebody-elses-app", "2026.101.10101", "dev");

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
  }

  @Test
  public void aDeploymentThatNamesNoVersionIsSkipped() {
    String tag = freshTag();
    QuarkusTransaction.requiringNew().run(() -> pendingTag(tag));

    deployments.onFrame(
        frame("{\"applicationName\":\"publisher\",\"environmentName\":\"dev\"}"));

    assertEquals(
        List.of(),
        merger.foldsOf("refs/heads/main"),
        "a deployer that sends no version correlates to nothing; it must not guess");
    assertNull(rowOf(tag).mergeRequestedAt, "and the tag is not gated by an event that said nothing");
  }

  // -----------------------------------------------------------------------------------------------
  // Failure
  // -----------------------------------------------------------------------------------------------

  @Test
  public void aConflictLeavesTheTagOwedWithItsReasonOnTheRowAndTheSweepLandsItLater() {
    String tag = freshTag();
    QuarkusTransaction.requiringNew().run(() -> pendingTag(tag));
    merger.answer(
        BackingBranchMerger.Outcome.conflict(
            "refs/heads/main",
            List.of(new BackingBranchMerger.Conflict("pom.xml", "refs/heads/main", "abc", "both"))));

    deploymentActive("publisher", tag, "dev");

    ReleasedTagPendingMerge stuck = rowOf(tag);
    assertNull(stuck.mergedAt, "no ref moved, so nothing reached main");
    assertNotNull(stuck.mergeRequestedAt, "but the gate passed and the merge is still owed");
    assertTrue(stuck.mergeDetail.contains("pom.xml"), stuck.mergeDetail);

    // Whatever resolved the conflict is a change this service cannot see; the sweep is what asks
    // again after it.
    merger.answerFreshMerges();
    finalization.sweep();

    ReleasedTagPendingMerge landed = rowOf(tag);
    assertNotNull(landed.mergedAt, "the retry landed it");
    assertNull(landed.mergeDetail, "and cleared the reason it used to carry");
    assertEquals(2, merger.foldsOf("refs/heads/main").size(), "one failed attempt, one that landed");
  }

  @Test
  public void anUnreachableGitHostIsAStillOwedMergeRatherThanALostOne() {
    String tag = freshTag();
    QuarkusTransaction.requiringNew().run(() -> pendingTag(tag));
    merger.answer(BackingBranchMerger.Outcome.unreachable("qits-githost answered 503"));

    deploymentActive("publisher", tag, "dev");

    assertNull(rowOf(tag).mergedAt);
    assertTrue(rowOf(tag).mergeDetail.contains("503"));

    merger.answerFreshMerges();
    finalization.sweep();

    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * A release still waiting on its deployment is not owed anything, and the sweep must leave it
   * alone: merging it would put the commit on {@code main} with nothing having proved it, which is
   * the shape this epic removed.
   */
  @Test
  public void theSweepDoesNotTouchATagWhoseDeploymentHasNotHappened() {
    QuarkusTransaction.requiringNew().run(() -> pendingTag(freshTag()));

    finalization.sweep();

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
  }

  // -----------------------------------------------------------------------------------------------
  // What a reader can see
  // -----------------------------------------------------------------------------------------------

  /**
   * The lifecycle's end, on the request that started it: a RELEASED request whose {@code
   * mergedToMainAt} is null is a release that shipped and has not reached {@code main} yet, which is
   * the only place that fact is queryable.
   */
  @Test
  public void aReleasedRequestSaysWhetherItsTagHasReachedMainYet() {
    String tag = freshTag();
    String requestId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest released = new ReleaseRequest();
              released.id = requestId;
              released.repoId = repoId;
              released.projectId = projectId;
              released.summary = "shipped";
              released.state = ReleaseRequest.State.RELEASED;
              released.version = tag;
              released.armedAt = Instant.now();
              released.createdAt = Instant.now();
              released.updatedAt = Instant.now();
              released.persist();
              pendingTag(tag).releaseRequestId = requestId;
            });

    given()
        .get(base() + "/" + requestId)
        .then()
        .statusCode(200)
        .body("request.version", org.hamcrest.Matchers.equalTo(tag))
        .body("request.mergedToMainAt", org.hamcrest.Matchers.nullValue());

    deploymentActive("publisher", tag, "dev");

    given()
        .get(base() + "/" + requestId)
        .then()
        .body("request.mergedToMainAt", org.hamcrest.Matchers.notNullValue());
    given()
        .get(base())
        .then()
        .body(
            "requests.find { it.id == '" + requestId + "' }.mergedToMainAt",
            org.hamcrest.Matchers.notNullValue());
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release in flight\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  /** A calver nothing else in the suite can be holding. */
  private static String freshTag() {
    return "2026.903." + (100000 + (int) (Math.random() * 800000));
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

  private ReleasedTagPendingMerge rowOf(String tag) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find(
                        "repoId = ?1 and tagName = ?2", repoId, tag)
                    .firstResult());
  }

  private void deploymentActive(String application, String version, String environment) {
    deployments.onFrame(
        frame(
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\""
                + application
                + "\",\"environmentName\":\""
                + environment
                + "\",\"environmentId\":\"env-"
                + environment
                + "\",\"version\":\""
                + version
                + "\",\"commitSha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\"}"));
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(), "DeploymentActive", Instant.now(), payload, null, null, null);
  }
}
