package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Auto Release: a met gate becomes a tag.</b> The whole executor, driven through the state
 * machine that calls it, against a git host that is a map.
 *
 * <p>The executor is reached by turning {@link RecordingReleaseExecutor#passThrough()} on: the
 * recording fake wins the port's injection in every other test in this package (which is what lets
 * them script an outcome), and delegating is what puts the <em>shipped</em> executor back under the
 * <em>real</em> {@code ReleaseRequests} — so the bookkeeping only that class does (the pending-merge
 * row, RELEASED, the siblings' re-fold) is proved to run behind a real release rather than behind a
 * scripted one.
 *
 * <p>The fixture repository is a two-module maven reactor, because the interesting half of the bump
 * is the {@code <module>} walk and the in-reactor {@code <parent><version>}; {@code
 * ManifestVersionBumpTest} owns the exhaustive manifest cases offline.
 */
@QuarkusTest
public class AutoReleaseTest {

  @Inject eu.wohlben.qits.projects.bus.BuildStatusListener buildListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseRequestAnnouncer requestAnnouncer;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseAnnouncer releases;

  @Inject RecordingQaRunCancellations cancellations;

  /** What {@link eu.wohlben.qits.projects.control.VersionStamp} may produce, and only that. */
  private static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{3,4}\\.\\d{1,6}");

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    requestAnnouncer.reset();
    gitHost.reset();
    releases.reset();
    cancellations.reset();
    activeBuilds.answer(Optional.of(1));
    repoId = "release-exec-repo-" + UUID.randomUUID();
    projectId = "release-exec-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "auto-release";
              project.slug = "auto-release-" + UUID.randomUUID();
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
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  // ---------------------------------------------------------------------------------------------
  // The fixture repository
  // ---------------------------------------------------------------------------------------------

  private static final String ROOT_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-thing-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <modules>
          <module>domain</module>
        </modules>
      </project>
      """;

  private static final String MODULE_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-thing-parent</artifactId>
          <version>1.0.0-SNAPSHOT</version>
        </parent>
        <artifactId>qits-thing-domain</artifactId>
      </project>
      """;

  private static Map<String, String> reactor() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("pom.xml", ROOT_POM);
    files.put("domain/pom.xml", MODULE_POM);
    files.put("README.md", "nothing to bump here");
    return files;
  }

  // ---------------------------------------------------------------------------------------------
  // Driving one release
  // ---------------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"the thing works now\"}")
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

  private void greenVerdict(String sha) {
    buildListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\"}",
            null,
            null,
            null));
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

  /**
   * Create a gated request over a staged tree and let the real executor release it. Returns the
   * request's id; the merged sha the tree was staged at is {@link #mergedSha}.
   */
  private String mergedSha;

  private String releaseARequest(Map<String, String> tree) {
    String id = create("work");
    mergedSha = mergedShaOf(id);
    assertNotNull(mergedSha, "the create folds the sources at once");
    gitHost.tree(mergedSha, tree);
    executor.passThrough();
    activeBuilds.answer(Optional.of(0));
    greenVerdict(mergedSha);
    return id;
  }

  // ---------------------------------------------------------------------------------------------
  // The happy path, step by step
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aMetGateStampsBumpsCommitsTagsDeletesAndAnnounces() {
    String id = releaseARequest(reactor());
    awaitState(id, "RELEASED");

    // 1. The stamp. One per attempt, and the request wears it.
    String version =
        given().get(base() + "/" + id).then().extract().path("request.version");
    assertTrue(CALVER.matcher(version).matches(), "not a calver: " + version);

    // 2 + 3. The bump, committed onto the BACKING BRANCH — the fold, not any one participant.
    assertEquals(1, gitHost.commits().size(), "one commit, and it is the last one before the tag");
    RecordingReleaseGitHost.Commit commit = gitHost.commits().get(0);
    assertEquals("refs/heads/release/" + id, commit.ref());
    assertEquals("release(" + version + "): the thing works now", commit.message());
    assertEquals(
        java.util.Set.of("pom.xml", "domain/pom.xml"),
        commit.files().keySet(),
        "every pom of the reactor, and the README is not a manifest");
    assertTrue(
        commit.files().get("pom.xml").contains("<version>" + version + "</version>"),
        commit.files().get("pom.xml"));
    assertTrue(
        commit.files().get("domain/pom.xml").contains("<version>" + version + "</version>"),
        "the in-reactor <parent><version> follows the root");

    // 4. The tag, on the BUMPED commit — the manifests inside it carry the version the tag says.
    assertEquals(List.of(version), gitHost.createdTags());
    assertEquals(commit.sha(), gitHost.tags().get(0).sha(), "what is tagged is the bump commit");

    // 5. The branches the release consumed. NEVER the default branch.
    assertEquals(
        List.of("work", "release/" + id),
        gitHost.deletedBranches(),
        "the named source and the backing branch, and nothing else");
    assertFalse(gitHost.deletedBranches().contains("main"));

    // 6. SCMRelease, the instant the tag was accepted, with the six payload fields.
    assertEquals(1, releases.announced().size());
    RecordingReleaseAnnouncer.Announced announced = releases.announced().get(0);
    assertEquals(projectId, announced.projectId());
    assertEquals(repoId, announced.repoId());
    assertEquals("release/" + id, announced.branch(), "what was released is the fold's branch");
    assertEquals(version, announced.version());
    assertNotNull(announced.occurredAt());
    // The coordinate that makes this event checkoutable, and it is the BUMP commit rather than the
    // fold: `branch` above names a ref this same operation deleted, and `version` names a tag and
    // not a commit, so before this field a release pipeline could only clone main and go looking.
    assertEquals(
        commit.sha(),
        announced.commitSha(),
        "the event says what the tag points at, and it agrees with the pending row");

    // And this service's own half: the tag joins the repository's implicit source set until
    // something merges it to main, so every other open request is a superset of what is shipping.
    ReleasedTagPendingMerge pending =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find("repoId = ?1", repoId)
                        .firstResult());
    assertNotNull(pending, "a released tag with no pending row would be invisible to the next fold");
    assertEquals(version, pending.tagName);
    assertEquals(commit.sha(), pending.releasedSha, "the row says what the tag points at");
    assertNull(pending.mergedAt, "nothing has merged it to main yet — that is a later arm");
  }

  @Test
  public void aRepositoryThatRendersNoVersionTagsTheFoldItselfAndIsAReleaseLikeAnyOther() {
    String id = releaseARequest(Map.of("README.md", "a docs repository", "docs/index.md", "hi"));
    awaitState(id, "RELEASED");

    assertEquals(List.of(), gitHost.commits(), "nothing renders a version, so nothing is committed");
    assertEquals(1, gitHost.tags().size());
    assertEquals(mergedSha, gitHost.tags().get(0).sha(), "the fold itself is what the tag names");
    assertEquals(1, releases.announced().size());
    assertEquals(
        mergedSha,
        releases.announced().get(0).commitSha(),
        "a stackless release has no commit before its tag, so the fold IS the checkout target —"
            + " the event carries one either way");
  }

  // ---------------------------------------------------------------------------------------------
  // tag-exists: the platform's version-uniqueness guarantee
  // ---------------------------------------------------------------------------------------------

  // ---------------------------------------------------------------------------------------------
  // The wrapper's estate
  // ---------------------------------------------------------------------------------------------

  private static final String LIB_HEAD = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String SVC_HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  private String libId;
  private String svcId;

  /**
   * The fixture repository becomes the project's WRAPPER with two named siblings — and one
   * declared submodule the catalog does not know, which must be skipped rather than pinned or
   * refused.
   */
  private Map<String, String> wrapperEstate() {
    libId = "estate-lib-" + UUID.randomUUID();
    svcId = "estate-svc-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository wrapper = Repository.findById(repoId);
              wrapper.archetype = RepositoryArchetype.PROJECT;
              Project project = Project.findById(projectId);
              alias(project, sibling(project, libId, "main"), "qits-thing-javalib");
              alias(project, sibling(project, svcId, "trunk"), "qits-thing-service");
            });
    gitHost.headOf(libId, "main", LIB_HEAD);
    gitHost.headOf(svcId, "trunk", SVC_HEAD);
    Map<String, String> tree = new LinkedHashMap<>();
    tree.put(
        ".gitmodules",
        """
        [submodule "qits-thing-javalib"]
        \tpath = components/qits-thing/qits-thing-javalib
        \turl = ../qits-thing-javalib.git
        [submodule "qits-thing-service"]
        \tpath = components/qits-thing/qits-thing-service
        \turl = ../qits-thing-service.git
        [submodule "qits-thing-stray"]
        \tpath = components/qits-thing/qits-thing-stray
        \turl = ../qits-thing-stray.git
        """);
    tree.put("README.md", "the estate");
    return tree;
  }

  private static Repository sibling(Project project, String id, String mainBranch) {
    Repository repository = new Repository();
    repository.id = id;
    repository.project = project;
    repository.mainBranch = mainBranch;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository, String name) {
    RepositoryName alias = new RepositoryName();
    alias.project = project;
    alias.repository = repository;
    alias.name = name;
    alias.persist();
  }

  @Test
  public void aWrapperReleaseBanksItsEstateAsGitlinkPinsInTheReleaseCommit() {
    String id = releaseARequest(wrapperEstate());
    awaitState(id, "RELEASED");

    assertEquals(1, gitHost.commits().size(), "the estate is one commit, the last before the tag");
    RecordingReleaseGitHost.Commit commit = gitHost.commits().get(0);
    assertEquals(Map.of(), commit.files(), "the wrapper renders no version; the commit is the pins");
    assertEquals(
        Map.of(
            "components/qits-thing/qits-thing-javalib", LIB_HEAD,
            "components/qits-thing/qits-thing-service", SVC_HEAD),
        commit.gitlinks(),
        "each declared submodule at its default branch's head; the stray one is skipped");
    assertEquals(commit.sha(), gitHost.tags().get(0).sha(), "the tag names the banked estate");
  }

  @Test
  public void anUnreadableSubmoduleHeadRefusesTheBankRatherThanPinningPartOfTheEstate() {
    Map<String, String> tree = wrapperEstate();
    gitHost.headUnreadable(
        svcId, "trunk", ReleaseGitHost.Answer.failedRetryable("qits-githost answered 503"));
    String id = releaseARequest(tree);
    awaitState(id, "FAILED");

    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("cannot be banked"), detail);
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    assertEquals(List.of(), gitHost.createdTags(), "half a bank must not release");
    assertEquals(List.of(), gitHost.commits(), "and nothing was committed either");
  }

  @Test
  public void aTakenVersionIsRestampedAndTheSecondAttemptRebumpsTheManifests() {
    gitHost.refuseTagsAsExisting(1);
    String id = releaseARequest(reactor());
    awaitState(id, "RELEASED");

    assertEquals(2, gitHost.tags().size(), "one refusal, one attempt more");
    assertEquals(
        ReleaseGitHost.TagResult.ALREADY_EXISTS, gitHost.tags().get(0).result());
    assertEquals(ReleaseGitHost.TagResult.CREATED, gitHost.tags().get(1).result());

    String version =
        given().get(base() + "/" + id).then().extract().path("request.version");
    assertEquals(gitHost.tags().get(1).name(), version, "the request wears the version that landed");

    // THE INVARIANT THE RE-STAMP EXISTS FOR: the tree the tag points at carries the version the tag
    // says. That is why a collision restarts the WHOLE attempt — reading the tree the previous one
    // left behind and bumping it again — rather than re-tagging the same commit under a new name,
    // which would ship artifacts naming a version nothing built.
    String tagged = gitHost.tags().get(1).sha();
    assertEquals(
        1,
        gitHost.treeAt(tagged).get("pom.xml").split(Pattern.quote(version), -1).length - 1,
        "the tagged commit's root pom carries the tag's version exactly once");
    assertTrue(
        gitHost.treeAt(tagged).get("domain/pom.xml").contains("<version>" + version + "</version>"),
        "and so does every other pom of the reactor");

    // Whether the second attempt needed a commit of its own depends on the clock: the stamp has
    // one-second resolution, so a re-stamp inside the same second produces the version the first
    // attempt's commit already wrote, the bump is a no-op and the tag names that commit. Both arms
    // satisfy the invariant above, which is why it is the invariant that is asserted and not a
    // commit count. (A collision across a second boundary writes a second commit.)
    assertTrue(gitHost.commits().size() >= 1 && gitHost.commits().size() <= 2);
    assertEquals(
        gitHost.commits().get(gitHost.commits().size() - 1).sha(),
        tagged,
        "whatever the count, what is tagged is the newest bump commit");
  }

  @Test
  public void aVersionThatStaysTakenGivesUpRetryablyRatherThanForever() {
    gitHost.refuseTagsAsExisting(99);
    String id = releaseARequest(reactor());
    awaitState(id, "FAILED");

    assertEquals(
        GitHostReleaseExecutor.ATTEMPTS,
        gitHost.tags().size(),
        "bounded: a name still taken on the third attempt is not a same-second tie");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    assertEquals(List.of(), releases.announced(), "nothing was released, so nothing is announced");
  }

  // ---------------------------------------------------------------------------------------------
  // Failures, and which of them the sweep may knock on again
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aFailureAtTheTagStepIsFailedRetryableAndTheSweepReleasesItAfterwards() {
    gitHost.failTagWith(
        ReleaseGitHost.TagAnswer.failedRetryable("qits-githost answered 503: <html>"));
    String id = releaseARequest(reactor());
    awaitState(id, "FAILED");

    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be tagged"), detail);
    assertTrue(detail.contains("503"), detail);
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    assertEquals(List.of(), gitHost.deletedBranches(), "a release that did not happen consumed nothing");
    assertEquals(List.of(), releases.announced());

    // The moment passes, and the sweep is what notices. The bump commit is re-made against the tip
    // the failed attempt left, which is why the retry is a whole attempt rather than a resumed one.
    gitHost.failTagWith(null);
    releaseRequests.sweep();
    awaitState(id, "RELEASED");
    assertEquals(1, gitHost.createdTags().size());
    assertEquals(1, releases.announced().size());
  }

  @Test
  public void aManifestThatWillNotParseIsARefusalTheSweepLeavesStanding() {
    Map<String, String> broken = reactor();
    broken.put("pom.xml", "<project><artifactId>oops</artifactId");
    String id = releaseARequest(broken);
    awaitState(id, "FAILED");

    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be stamped"), detail);
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(false));

    int attempts = gitHost.tags().size();
    releaseRequests.sweep();
    releaseRequests.sweep();
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertEquals("FAILED", stateOf(id), "a malformed pom answers the same on every knock");
    assertEquals(attempts, gitHost.tags().size(), "so the sweep made no further attempt");
  }

  @Test
  public void anUnreachableGitHostAtTheREADIsRetryableRatherThanARefusal() {
    gitHost.failTreeWith(
        ReleaseGitHost.Answer.failedRetryable("qits-githost could not be reached"));
    String id = releaseARequest(reactor());
    awaitState(id, "FAILED");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be read"), detail);
  }
}
