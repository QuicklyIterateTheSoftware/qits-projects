package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.eventstream.control.EventFrame;
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
 * <b>The non-deployable shortcut: a library's release reaches {@code main} when it is published.</b>
 *
 * <p>The claim worth pinning is the negative one — <b>a repository that declares a deployment is
 * left entirely alone here</b>. Merging on publication would put the commit on {@code main} before
 * the deployment that justifies it, which is the ordering this whole epic exists to fix, and the
 * only thing standing between the two behaviours is one file's presence in the released tree.
 */
@QuarkusTest
public class NonDeployablePublishTest {

  @Inject eu.wohlben.qits.projects.bus.SoftwareReleaseListener publications;

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
  public void aRepositoryThatDeclaresNoDeploymentReachesMainOnPublication() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    treeAtTag(tag, "pom.xml", "README.md");

    published(tag, "maven", "eu.wohlben.qits:qits-thing");

    List<RecordingBackingBranchMerger.Fold> intoMain = merger.foldsOf("refs/heads/main");
    assertEquals(1, intoMain.size(), "a library has no deployment to wait for");
    assertEquals(List.of(releasedSha), intoMain.get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  @Test
  public void aRepositoryThatDeclaresADeploymentIsLeftToItsDeployment() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml", ".config/qits/deployments.yml");

    published(tag, "docker", "qits/qits-thing");

    assertEquals(
        List.of(),
        merger.foldsOf("refs/heads/main"),
        "publishing an image is not the same statement as that image serving");
    assertNull(
        rowOf(tag).mergeRequestedAt, "and the tag is not even gated: the deployment gates it");

    // And then the deployment arrives, which is the gate this release was always waiting for.
    deploymentActive("qits-thing", tag);

    assertEquals(1, merger.foldsOf("refs/heads/main").size());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * One release publishes several artifacts and qits-ci announces one event each. The first decides;
   * the rest must not even ask the git host — which is what the scripted tree failure proves, since
   * a read that happened would be a retryable answer and would throw.
   */
  @Test
  public void severalPublishedArtifactsOfOneReleaseMergeOnceAndReadTheTreeOnce() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml");

    published(tag, "maven", "eu.wohlben.qits:qits-thing");
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("nobody may ask a second time"));

    assertDoesNotThrow(() -> published(tag, "npm", "@qits/thing"));
    assertDoesNotThrow(() -> published(tag, "docker", "qits/thing"));

    assertEquals(1, merger.foldsOf("refs/heads/main").size(), "one release, one merge to main");
  }

  @Test
  public void aGitHostThatCannotSayWhetherItDeploysLeavesTheEventOwed() {
    String tag = freshTag();
    pendingTag(tag);
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("qits-githost answered 503"));

    assertThrows(
        RuntimeException.class,
        () -> published(tag, "maven", "eu.wohlben.qits:qits-thing"),
        "a throw is how this seam says 'ask me again': answering the question wrongly either"
            + " finalizes main ahead of a deployment or never finalizes it at all");

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);

    // The catch-up offers it again once the git host is back, and it lands.
    gitHost.reset();
    treeAtTag(tag, "pom.xml");
    published(tag, "maven", "eu.wohlben.qits:qits-thing");

    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * A refusal that is not about the moment — a tag this git host does not know — is settled instead:
   * the same bytes would fail identically forever, and the released tag stays visibly unfinished for
   * a deployment or a person to complete.
   */
  @Test
  public void aTagTheGitHostDoesNotKnowSettlesWithoutFinalizingAnything() {
    String tag = freshTag();
    pendingTag(tag);

    assertDoesNotThrow(() -> published(tag, "maven", "eu.wohlben.qits:qits-thing"));

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);
    assertNull(rowOf(tag).mergedAt);
  }

  @Test
  public void aPublicationOfSomethingThisServiceNeverReleasedAsksTheGitHostNothing() {
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("nobody should be asking"));

    assertDoesNotThrow(() -> published("2026.101.10101", "maven", "somebody:else"));

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

  private void published(String version, String packageType, String packageName) {
    publications.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SoftwareRelease",
            Instant.now(),
            "{\"repository\":\""
                + repoId
                + "\",\"repoId\":\""
                + repoId
                + "\",\"projectId\":\""
                + projectId
                + "\",\"version\":\""
                + version
                + "\",\"packageType\":\""
                + packageType
                + "\",\"packageName\":\""
                + packageName
                + "\"}",
            null,
            null,
            null));
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
