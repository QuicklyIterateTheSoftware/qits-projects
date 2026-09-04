package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The non-deployable shortcut: a repository that deploys nothing reaches {@code main} at its own
 * release.</b>
 *
 * <p>Two claims, and the negative one is the older: <b>a repository that declares a deployment is
 * left entirely alone</b>, because merging at the release would put the commit on {@code main}
 * before the deployment that justifies it, which is the ordering this epic exists to fix. The only
 * thing standing between the two behaviours is one file's presence in the released tree.
 *
 * <p>The newer claim is <b>where the fork hangs</b> (2026-09-04). It used to hang off qits-ci's
 * {@code SoftwareRelease} — an event only a repository carrying a {@code ci-event-release.yml}
 * recipe ever emits — so every recipe-less repository, every SPA among them, released tags that
 * never reached {@code main} at all. It hangs off this service's own release now, which is a fact it
 * always has, and the catch-up sweep is what heals everything stranded in the meantime.
 */
@QuarkusTest
public class NonDeployablePublishTest {

  @Inject ReleaseFinalization finalization;

  @Inject eu.wohlben.qits.projects.bus.DeploymentActiveListener deployments;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    activeBuilds.answer(Optional.of(1));
    repoId = "publish-lib-repo-" + UUID.randomUUID();
    projectId = "publish-lib-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "publish-lib";
              project.slug = "publish-lib-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /**
   * Nothing of this fixture may outlive the class: the finalization sweep walks every ungated and
   * every owed row in the database, so a row left behind is a git-host call inside somebody else's
   * test.
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

  @Test
  public void aRepositoryThatDeclaresNoDeploymentReachesMainAtItsRelease() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    treeAtTag(tag, "pom.xml", "README.md");

    finalization.onReleased(repoId, tag);

    List<RecordingBackingBranchMerger.Fold> intoMain = merger.foldsOf("refs/heads/main");
    assertEquals(1, intoMain.size(), "nothing deploys this, so nothing else will ever gate it");
    assertEquals(List.of(releasedSha), intoMain.get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * The SPA case, which is the one that was broken: a frontend repository publishes no artifact
   * event at all, so the old {@code SoftwareRelease} gate never fired for it and its tag sat off
   * {@code main} for ever. It declares no deployment either, so it takes exactly the arm above.
   */
  @Test
  public void aRepositoryThatPublishesNoArtifactEventIsFinalizedAllTheSame() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "package.json", "angular.json", "src/main.ts");

    finalization.onReleased(repoId, tag);

    assertEquals(1, merger.foldsOf("refs/heads/main").size());
    assertNotNull(rowOf(tag).mergedAt, "a released SPA tag must not sit off main for ever");
  }

  @Test
  public void aRepositoryThatDeclaresADeploymentIsLeftToItsDeployment() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml", ".config/qits/deployments.yml");

    finalization.onReleased(repoId, tag);

    assertEquals(
        List.of(),
        merger.foldsOf("refs/heads/main"),
        "a release is not the same statement as what it released serving");
    assertNull(
        rowOf(tag).mergeRequestedAt, "and the tag is not even gated: the deployment gates it");

    // And then the deployment arrives, which is the gate this release was always waiting for.
    deploymentActive("qits-thing", tag);

    assertEquals(1, merger.foldsOf("refs/heads/main").size());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * The fork is made once. A second telling — the release path and the catch-up racing, a replayed
   * anything — must not even ask the git host, which is what the scripted tree failure proves: a
   * read that happened would answer "retryable" and nothing would merge.
   */
  @Test
  public void theSecondTellingOfOneReleaseAsksTheGitHostNothing() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml");

    finalization.onReleased(repoId, tag);
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("nobody may ask a second time"));

    assertDoesNotThrow(() -> finalization.onReleased(repoId, tag));
    finalization.sweep();

    assertEquals(1, merger.foldsOf("refs/heads/main").size(), "one release, one merge to main");
  }

  /**
   * <b>The catch-up.</b> A git host that could not say whether the repository deploys leaves the tag
   * ungated rather than guessing — and the release, which has already happened, is never failed by
   * it. The sweep is what asks again, and it is the same sweep that heals every tag stranded by the
   * gate this fork replaced.
   */
  @Test
  public void aGitHostThatCannotAnswerLeavesTheTagUngatedAndTheCatchUpHealsIt() {
    String tag = freshTag();
    pendingTag(tag);
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("qits-githost answered 503"));

    assertDoesNotThrow(
        () -> finalization.onReleased(repoId, tag),
        "a tag exists by now; nothing after it may fail the release that made it");

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);

    gitHost.reset();
    treeAtTag(tag, "pom.xml");
    finalization.sweep();

    assertNotNull(rowOf(tag).mergedAt, "the catch-up asked again and it landed");
  }

  /**
   * A released tag whose fork never ran at all — the process died between the two, or the release
   * predates the fork living here — is exactly what the catch-up is for, and it needs no event and
   * no operator.
   */
  @Test
  public void theCatchUpFinalizesAReleaseNothingEverForkedOn() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    treeAtTag(tag, "package.json");

    finalization.sweep();

    assertEquals(List.of(releasedSha), merger.foldsOf("refs/heads/main").get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * And the catch-up's own negative: a deployable release still waiting on its deployment is looked
   * at on every sweep and left exactly where it is. Merging it would be this class advancing {@code
   * main} on no gate at all.
   */
  @Test
  public void theCatchUpLeavesADeployableTagWaitingForItsDeployment() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml", ".config/qits/deployments.yml");

    finalization.sweep();
    finalization.sweep();

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);
    assertNull(rowOf(tag).mergedAt);
  }

  /**
   * A refusal that is not about the moment — a tag this git host does not know — finalizes nothing
   * either: the released tag stays visibly unfinished for a deployment, a later sweep or a person to
   * complete, and is never read as "this repository deploys nothing".
   */
  @Test
  public void aTagTheGitHostDoesNotKnowSettlesWithoutFinalizingAnything() {
    String tag = freshTag();
    pendingTag(tag);

    assertDoesNotThrow(() -> finalization.onReleased(repoId, tag));

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);
    assertNull(rowOf(tag).mergedAt);
  }

  @Test
  public void aVersionThisServiceNeverReleasedAsksTheGitHostNothing() {
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("nobody should be asking"));

    assertDoesNotThrow(() -> finalization.onReleased(repoId, "2026.101.10101"));

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private static String freshTag() {
    return "2026.903." + (100000 + (int) (Math.random() * 800000));
  }

  /** A released tag of the fixture repository, in flight. Answers the sha it points at. */
  private String pendingTag(String tag) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
              row.id = UUID.randomUUID().toString();
              row.repoId = repoId;
              row.tagName = tag;
              row.releasedSha = RecordingBackingBranchMerger.freshSha();
              row.releasedAt = Instant.now();
              row.persist();
              return row.releasedSha;
            });
  }

  /** The released tree, as the git host would list it — the paths are the whole of what is read. */
  private void treeAtTag(String tag, String... paths) {
    Map<String, String> tree = new LinkedHashMap<>();
    for (String path : paths) {
      tree.put(path, "irrelevant");
    }
    gitHost.tree("refs/tags/" + tag, tree);
  }

  private ReleasedTagPendingMerge rowOf(String tag) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find(
                        "repoId = ?1 and tagName = ?2", repoId, tag)
                    .firstResult());
  }

  private void deploymentActive(String application, String version) {
    deployments.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\""
                + application
                + "\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\"}",
            null,
            null,
            null));
  }
}
