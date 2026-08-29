package eu.wohlben.qits.projects.stories.review;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.GitHostFixture;
import eu.wohlben.qits.projects.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.projects.stories.catalogue.ProjectCatalogueIT;
import eu.wohlben.qits.projects.stories.planning.EpicPlanningIT;
import eu.wohlben.qits.projects.stories.refusals.AccessRefusalIT;
import eu.wohlben.qits.projects.stories.support.StoryGitHost;
import eu.wohlben.qits.projects.stories.support.StoryIdentities;
import eu.wohlben.qits.projects.stories.support.StoryPlatform;
import eu.wohlben.qits.projects.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>What reading costs</b> — the two answers this service's read surface gives, and they are
 * different answers.
 *
 * <p>Almost everything a person opens here is served out of this service's own two databases: the
 * projects overview, the flat repository catalogue, one repository by id, a project's epics. Those
 * reach <b>nothing</b>, and only a count and an actor set can say so — a presence check cannot
 * express "and no other arrow exists".
 *
 * <p>One read is not like the others, and this class exists to say which. {@code GET
 * /projects/{id}/repositories} answers with the project's components <em>joined to the wrapper's
 * manifest</em>, and the manifest is a file in a git repository rather than a column — so the read
 * refreshes the wrapper's mirror from the git host before it can answer. That is a real dependency
 * of a screen people leave open, it belongs in the diagram, and hiding it inside the first story's
 * "reads reach nothing" would have made that claim false.
 *
 * <p><b>That refresh is throttled, which is why the second story waits before it reads.</b> {@code
 * RepoMirror.refresh()} trusts a mirror fetched inside {@code qits.projects.git.mirror-freshness-ms}
 * (5s), so whether a given request pays for a fetch depends on how long the neighbouring story took
 * — an edge that comes and goes between runs, moving the {@code networkHash} with nothing having
 * changed. {@link StoryPlatform#awaitMirrorFreshnessLapse()} is what turns it back into a fact
 * about the route.
 *
 * <p>Both stories are a <b>person's</b>: the platform edge asserts {@code X-Qits-User} and {@code
 * X-Qits-Roles}, this service authenticates no human itself, and the OIDC tenant is bearer-only so
 * a request with no {@code Authorization} header falls through to the header mechanism exactly as it
 * does behind the edge. That is what makes "an operator" an honest name for the arrow.
 *
 * <p>It runs last of this repository's story classes on purpose: it reads what {@link
 * ProjectCatalogueIT} and {@link EpicPlanningIT} put there, which is the state a real operator
 * would be looking at.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(GitHostFixture.class)
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CatalogueReviewIT {

  static final String CATEGORY = "operations";

  static final String READS_SLUG = "an-operator-reviews-the-catalogue-and-reaches-nothing";
  static final String MANIFEST_SLUG = "opening-a-project-s-components-refreshes-the-wrapper-s-mirror";

  /** The person reading, through the edge's forward-auth headers. */
  static final String OPERATOR = "an operator";

  static final String OPERATOR_USER = "sam";

  /** The inbound tap, once — the framework's own, idempotent per service. */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * Provision first, tap the far side second — and both in {@code @BeforeEach} rather than
   * {@code @BeforeAll}, which is not a preference: {@link StoryPlatform} drives the API at {@code
   * RestAssured.port}, and the Quarkus integration-test extension sets that port in its beforeEach
   * callback and clears it in afterEach, so a {@code @BeforeAll} here would build a URL reading
   * {@code http://localhost:-1}. Both calls are idempotent per JVM. See
   * {@link eu.wohlben.qits.projects.stories.refusals.AccessRefusalIT} for the whole reasoning, and
   * {@link StoryGitHost} for why the ORDER of the two is what keeps the fixture out of every
   * diagram.
   */
  @BeforeEach
  void provisionThePlatformThenFloorTheGitHostRecording() {
    StoryPlatform.provision();
    StoryGitHost.install();
  }

  @UserStory(value = "An operator reviews the catalogue and reaches nothing", category = CATEGORY)
  @UserStoryDescription(
      """
      The walk somebody takes when they want to know what the platform holds: the projects
      overview, the flat repository catalogue a machine enumerates the platform with, one
      repository opened by id — the only way in from a deep link that carries no project — and a
      project's epics. Four reads, four rows-out-of-a-database answers, and not one call to any
      other service. That is the claim worth making about a read surface, and it is a claim about
      absence: the count and the single initiator are what say it.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ProjectCatalogueIT.class,
    EpicPlanningIT.class,
    AccessRefusalIT.class
  })
  @Order(1)
  void anOperatorReviewsTheCatalogue(Interactions story) {
    NetworkCapture.actor(OPERATOR);
    String projectId = StoryPlatform.projectId();
    String componentId = StoryPlatform.componentRepositoryId();

    List<Map<String, Object>> projects =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.PROJECTS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entries");
    assertTrue(
        projects.stream()
            .anyMatch(entry -> projectId.equals(((Map<?, ?>) entry.get("project")).get("id"))),
        "the overview names the project this run created");
    story.note("the operator opens the projects overview").as("projects-listed");

    List<Map<String, Object>> repositories =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.REPOSITORIES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("repositories");
    Map<String, Object> component =
        repositories.stream()
            .filter(entry -> componentId.equals(entry.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the flat catalogue omits a repository it holds"));
    assertEquals(
        StoryPlatform.COMPONENT_NAME,
        component.get("name"),
        "the catalogue carries the pair that addresses a repository publicly: project and name");
    assertEquals(projectId, component.get("projectId"));
    story
        .note("…and the flat catalogue, which is what a machine enumerates the platform with")
        .as("catalogue-listed");

    JsonPath repository =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.repositoryPath(componentId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(StoryPlatform.COMPONENT_NAME, repository.getString("repository.name"));
    assertEquals(
        StoryPlatform.DEFAULT_BRANCH,
        repository.getString("repository.mainBranch"),
        "the branch a caller reads by default");
    story
        .note("…then one repository by id, which is the only way in from a deep link")
        .as("repository-opened");

    assertNotNull(
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.projectEpicsPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entries"),
        "a project with no epics answers with an empty list, never with nothing");
    story.note("…and the project's plan, which is rows in a second database").as("epics-listed");
  }

  @UserStory(
      value = "Opening a project's components refreshes the wrapper's mirror",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The one read on this surface that is not free, and the reason the story before it had to
      leave this route out. A project's component list is not a table: it is the rows joined to
      what the project's WRAPPER repository declares in its .gitmodules, because a repository the
      wrapper does not name is not part of the project. The manifest is a file in a git repository,
      so answering means refreshing the wrapper's mirror from the platform's git host first —
      which is what the second arrow in this diagram is. It is throttled: a mirror fetched inside
      the freshness window is trusted as it stands, so this costs at most one fetch per wrapper per
      window rather than one per request. Worth knowing before anyone puts the route behind a poll
      faster than that.
      """)
  @Order(2)
  void openingTheComponentListReachesTheGitHost(Interactions story) {
    NetworkCapture.actor(OPERATOR);
    String projectId = StoryPlatform.projectId();
    // Before the read, not inside it: the mirror is only refetched once its freshness window has
    // lapsed, so a story that did not wait would document a fetch that happened to be due rather
    // than the dependency itself. See StoryPlatform#awaitMirrorFreshnessLapse.
    StoryPlatform.awaitMirrorFreshnessLapse();

    JsonPath listing =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.projectRepositoriesPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    List<Map<String, Object>> entries = listing.getList("entries");
    assertTrue(entries.size() >= 2, "the wrapper and at least the component the fixture added");
    assertEquals(
        StoryPlatform.wrapperRepositoryId(),
        listing.getString("wrapper.repositoryId"),
        "the manifest read is the project's own wrapper repository");
    assertTrue(
        listing.getList("wrapper.entries.name", String.class).contains(StoryPlatform.COMPONENT_NAME),
        "the wrapper's .gitmodules names the component, which is what membership means here");
    assertTrue(
        entries.stream()
            .filter(
                entry ->
                    StoryPlatform.componentRepositoryId()
                        .equals(((Map<?, ?>) entry.get("repository")).get("id")))
            .allMatch(entry -> Boolean.TRUE.equals(entry.get("declared"))),
        "a repository the manifest names is declared — the row and the file agree");
    story
        .note("the component list joins the project's rows to what its wrapper declares")
        .as("manifest-joined");

    // The mirror refresh happens inside the request, so this only guards the last bytes reaching
    // the recording — but a line that lands after the drain is a line in the next story's diagram.
    StoryGitHost.awaitRead(
        StoryGitHost.fetchAdvertisementPath(StoryPlatform.wrapperRepositoryId()));
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the reads that reach nothing -----------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, READS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, READS_SLUG, "projects-listed");
    ReportAssertions.assertStepId(CATEGORY, READS_SLUG, "catalogue-listed");
    ReportAssertions.assertStepId(CATEGORY, READS_SLUG, "repository-opened");
    ReportAssertions.assertStepId(CATEGORY, READS_SLUG, "epics-listed");
    ReportAssertions.assertEdge(
        CATEGORY,
        READS_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.PROJECTS_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        READS_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.REPOSITORIES_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        READS_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.repositoryPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        READS_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.projectEpicsPath("{id}") + " -> 200");
    // Four arrows, one person, and nothing left this process. That is the whole story.
    ReportAssertions.assertEdgeCount(CATEGORY, READS_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, READS_SLUG, List.of(OPERATOR));
    ReportAssertions.assertNoEdgesTo(CATEGORY, READS_SLUG, StoryGitHost.SERVICE_NAME);

    // --- the read that is not free --------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, MANIFEST_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, MANIFEST_SLUG, "manifest-joined");
    ReportAssertions.assertEdge(
        CATEGORY,
        MANIFEST_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.projectRepositoriesPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        MANIFEST_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", StoryGitHost.fetchAdvertisementPath("{id}"), 200));
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, MANIFEST_SLUG, List.of(OPERATOR, StoryTarget.SERVICE));
  }
}
