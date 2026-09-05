package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a release published, read out of the released tag's own tree.</b>
 *
 * <p>The tree is the source of every answer here, and that is the claim worth pinning: the recipes
 * are files in the repository at the tag, so this endpoint answers for a release whose CI announced
 * nothing, for one made before the endpoint existed, and for a repository that publishes nothing at
 * all. Nothing about a build's record is consulted, which is why none of these tests stage one.
 *
 * <p>The second claim is that <b>none of it is an error</b>. Not released, a tag the host cannot
 * read, a recipe that will not parse — each is a 200 carrying a sentence, because the page asking
 * this question is drawing a panel and "we could not ask" is a thing it can say.
 */
@QuarkusTest
public class ReleaseArtifactsTest {

  @Inject RecordingReleaseGitHost gitHost;

  private static final String VERSION = "2026.904.161524";
  private static final String RELEASED_SHA = "9f1c2b3d4e5f60718293a4b5c6d7e8f901234567";
  private static final String MERGED_SHA = "20c377ee71fabe6f32429d1506989efecec7798b";

  private static final String RECIPE = ".config/qits/ci-event-release.yml";
  private static final String QA_RECIPE = ".config/qits/ci-event-release-request.yml";
  private static final String DEPLOYMENTS = ".config/qits/deployments.yml";

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    gitHost.reset();
    repoId = "artifacts-repo-" + UUID.randomUUID();
    projectId = "artifacts-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "artifacts";
              project.slug = "artifacts-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /**
   * Nothing of this fixture may outlive the class: the finalization sweep walks every ungated row in
   * the database, so a released tag left behind is a git-host call inside somebody else's test.
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

  /**
   * The whole of what tells a service apart from a library here, and it is one file's presence — the
   * same reading {@code ReleaseFinalization} forks the publish phase on, so the two can never
   * disagree about what "deploys" means.
   */
  @Test
  public void aReleasedTreeDeclaringADeploymentIsDeployable() {
    String id = release();
    tree(DEPLOYMENTS, "pom.xml");

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("deployable", equalTo(true))
        .body("version", equalTo(VERSION))
        .body("releasedSha", equalTo(RELEASED_SHA));
  }

  /** And the other side of the same one file: a library declares none, so nothing deploys it. */
  @Test
  public void aReleasedTreeDeclaringNoDeploymentIsNot() {
    String id = release();
    tree("pom.xml", "README.md");

    given().get(artifactsOf(id)).then().statusCode(200).body("deployable", equalTo(false));
  }

  /** The declaration is forwarded, entry for entry, at the version the release landed as. */
  @Test
  public void theReleaseRecipesDeclarationIsWhatTheAnswerCarries() {
    String id = release();
    tree(
        Map.of(
            DEPLOYMENTS,
            "resources: []\n",
            RECIPE,
            """
            event: SCMRelease
            when:
              - repository: { exact: qits-thing-service }
            artifacts:
              - { type: docker, name: qits/qits-thing }
              - { type: maven, name: eu.wohlben.qits:qits-thing-domain }
            steps:
              - image: qits/build-images/node-docker-base:latest
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("detail", nullValue())
        .body("artifacts.type", contains("docker", "maven"))
        .body("artifacts.name", contains("qits/qits-thing", "eu.wohlben.qits:qits-thing-domain"))
        .body("artifacts.version", contains(VERSION, VERSION));
  }

  /**
   * <b>A repository with no recipe published nothing, and that is an answer rather than a problem.</b>
   * Every SPA on this platform is in exactly this case, so a sentence on {@code detail} here would
   * turn the ordinary outcome into a warning on half the release pages.
   */
  @Test
  public void aRepositoryThatDeclaresNoRecipeAnswersEmptyWithNothingToExplain() {
    String id = release();
    tree("package.json", "angular.json", "src/main.ts");

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts", hasSize(0))
        .body("detail", nullValue())
        .body("deployable", equalTo(false));
  }

  /**
   * A recipe that will not parse is said out loud instead of quietly answering a shorter list than
   * the repository declares — the difference between "this published nothing" and "we cannot read
   * what it published" is the whole reason {@code detail} exists.
   */
  @Test
  public void aRecipeThatWillNotParseIsASentenceAndNeverAShorterList() {
    String id = release();
    tree(Map.of(RECIPE, "event: SCMRelease\nartifacts: a-string-is-not-a-list\n"));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts", hasSize(0))
        .body("detail", containsString("readable artifact list"));
  }

  /**
   * <b>The userflow bundle is derived, and its version is the FOLD's sha.</b> That pipeline runs per
   * release request and publishes at {@code $QITS_CI_SHA}, so asking for the calver would 404 on a
   * bundle that is certainly there. Its site name comes out of the recipe too: {@code
   * qits-projects-service} publishes {@code @userflows/qits-projects}, so a name composed from the
   * repository's own would be a link to nothing.
   */
  @Test
  public void theUserflowBundleRidesAlongAtTheFoldsShaAndUnderTheNameTheRecipeSpells() {
    String id = release();
    tree(
        Map.of(
            RECIPE,
            "event: SCMRelease\nartifacts:\n  - { type: docker, name: qits/qits-thing }\n",
            QA_RECIPE,
            """
            event: ReleaseRequestChanged
            steps:
              - script: |
                  curl -X PUT "$QITS_DOCS_URL/@userflows/qits-thing/-/$QITS_CI_SHA"
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts.type", contains("docker", "userflows"))
        .body("artifacts.name", contains("qits/qits-thing", "@userflows/qits-thing"))
        .body("artifacts.version", contains(VERSION, MERGED_SHA));
  }

  /** A QA pipeline that publishes no bundle contributes nothing, which is most repositories. */
  @Test
  public void aQaPipelineThatPublishesNoBundleAddsNothing() {
    String id = release();
    tree(Map.of(QA_RECIPE, "event: ReleaseRequestChanged\nsteps:\n  - script: ./mvnw verify\n"));

    given().get(artifactsOf(id)).then().statusCode(200).body("artifacts", hasSize(0));
  }

  /**
   * The question is asked of every request a page draws, so a request that has not released has to
   * have an answer — and "nothing yet" is one. A 404 or a 409 here would make the panel's ordinary
   * state an error.
   */
  @Test
  public void anUnreleasedRequestIsToldSoRatherThanRefused() {
    String id = pending();

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("version", nullValue())
        .body("releasedSha", nullValue())
        .body("deployable", equalTo(false))
        .body("artifacts", hasSize(0))
        .body("detail", equalTo("Not released yet"));
  }

  /**
   * A tag the git host cannot read is a fact about the moment and not about the release, so the
   * version still travels and the reason is on the answer. Never a 500: this read sits behind a
   * panel, not behind a decision.
   */
  @Test
  public void aTagTheGitHostCannotReadAnswersWithItsOwnWords() {
    String id = release();
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("qits-githost answered 503"));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("version", equalTo(VERSION))
        .body("artifacts", hasSize(0))
        .body("detail", containsString("503"));
  }

  /** The scope is part of the address: another repository's route does not answer for this one. */
  @Test
  public void aRequestReadThroughTheWrongRepositoryIsNotFound() {
    String id = release();

    given()
        .get("/projects/api/repositories/somebody-else/release-requests/" + id + "/artifacts")
        .then()
        .statusCode(404);
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private String artifactsOf(String requestId) {
    return "/projects/api/repositories/" + repoId + "/release-requests/" + requestId + "/artifacts";
  }

  /** A landed release of the fixture repository, with the tag row the release recorded. */
  private String release() {
    String id = row(ReleaseRequest.State.RELEASED, VERSION);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasedTagPendingMerge tag = new ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = repoId;
              tag.tagName = VERSION;
              tag.releasedSha = RELEASED_SHA;
              tag.releaseRequestId = id;
              tag.releasedAt = Instant.now();
              tag.persist();
            });
    return id;
  }

  private String pending() {
    return row(ReleaseRequest.State.PENDING, null);
  }

  private String row(ReleaseRequest.State state, String version) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest request = new ReleaseRequest();
              request.id = UUID.randomUUID().toString();
              request.repoId = repoId;
              request.projectId = projectId;
              request.repoName = "qits-thing-service";
              request.summary = "a release worth reading";
              request.state = state;
              request.version = version;
              request.mergedSha = MERGED_SHA;
              request.createdAt = Instant.now();
              request.armedAt = request.createdAt;
              request.updatedAt = request.createdAt;
              request.persist();
              return request.id;
            });
  }

  /** The released tree as paths alone — what the deployability read is the whole of. */
  private void tree(String... paths) {
    Map<String, String> files = new LinkedHashMap<>();
    for (String path : paths) {
      files.put(path, "irrelevant");
    }
    gitHost.tree("refs/tags/" + VERSION, files);
  }

  /** The released tree with content, for the paths that are read rather than only listed. */
  private void tree(Map<String, String> files) {
    gitHost.tree("refs/tags/" + VERSION, files);
  }
}
