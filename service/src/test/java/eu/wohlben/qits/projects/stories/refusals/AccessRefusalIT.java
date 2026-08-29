package eu.wohlben.qits.projects.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.projects.api.GitHostFixture;
import eu.wohlben.qits.projects.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.projects.stories.planning.EpicPlanningIT;
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
import io.restassured.http.ContentType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Who may do what here</b> — the role table in this repository's AGENTS.md, walked as three
 * stories, and none of them provable anywhere else in this repository.
 *
 * <p>That last point is the whole reason these are integration tests. Inside a {@code @QuarkusTest}
 * the shipped {@code %test} dev user holds all four platform roles, so every door in this service is
 * open to a plain {@code given()} and a refusal cannot be observed at all. A launched artifact runs
 * in {@code NORMAL} mode with no dev user and the OIDC tenant on, which is the first moment
 * {@code qits:admin} and {@code qits:system} mean different things.
 *
 * <p>The three stories are the three answers this surface gives:
 *
 * <ol>
 *   <li>a <b>person</b> is refused the two machine-only doors — the bootstrap's adopt and the git
 *       host's name resolution — because both take a coordinate only the machine that minted it
 *       holds, and a browser has nothing to put in either;
 *   <li>a <b>machine</b> is refused the planning surface, which is a person's judgement about
 *       scope rather than a peer's integration point;
 *   <li>an <b>unauthenticated</b> caller reaches nothing at all, and answers 401 rather than 403,
 *       because there is no caller to have been forbidden.
 * </ol>
 *
 * <p>Each story also makes the claim a presence check cannot: {@code assertNoEdgesTo(qits-githost)}.
 * A refusal is decided at the door, so the git host is never asked — and a route that reached it
 * before deciding would be doing work on behalf of a caller it was about to refuse.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(GitHostFixture.class)
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AccessRefusalIT {

  static final String CATEGORY = "authorization";

  static final String PERSON_SLUG = "a-browser-session-never-opens-the-bootstrap-s-doors";
  static final String MACHINE_SLUG = "a-platform-service-s-bearer-never-rewrites-the-plan";
  static final String ANONYMOUS_SLUG = "an-unauthenticated-caller-reaches-nothing-at-all";

  /** The three initiators, one per story. */
  static final String ADMIN = "a signed-in administrator";

  static final String PLATFORM = "a platform service";

  static final String ANONYMOUS = "an unauthenticated caller";

  /** Kept so {@code @AfterAll} can assert the bearer is not in the published bundle. */
  private static String platformBearer;

  /**
   * The inbound tap, once. The framework's own, idempotent per service, so installing it here as
   * well as in {@link TokenValidationBootstrapIT} draws nothing twice.
   */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * Provision first, tap the far side second — and both in {@code @BeforeEach} rather than
   * {@code @BeforeAll}, which is not a preference.
   *
   * <p>{@link StoryPlatform} drives the API with a plain {@code HttpClient} at {@code
   * RestAssured.port}, and the Quarkus integration-test extension sets that port in its
   * <b>beforeEach</b> callback and clears it back to {@code -1} in afterEach — so a
   * {@code @BeforeAll} here builds a URL reading {@code http://localhost:-1}. Both calls are
   * idempotent per JVM, so this runs the fixture once, before the first story of whichever class
   * runs first, and no-ops for every story after.
   *
   * <p>The order within it is the load-bearing part: {@link StoryGitHost#install()} takes the end
   * of the git host's recording as its floor, so the fixture's own publishing is in no story's
   * diagram while everything a story causes is.
   */
  @BeforeEach
  void provisionThePlatformThenFloorTheGitHostRecording() {
    StoryPlatform.provision();
    StoryGitHost.install();
  }

  @UserStory(value = "A browser session never opens the bootstrap's doors", category = CATEGORY)
  @UserStoryDescription(
      """
      Two routes on this service are qits:system alone, and both take a coordinate a person does
      not have: the adopt door is handed the git host's opaque storage id by the machine that
      created the bare, and the by-name resolution is qits-githost turning a clone url's segment
      into that same id. An administrator's session is refused both — a method-level role REPLACES
      the class-level one rather than adding to it — while the ordinary projects overview, which
      names both roles, opens for exactly the same session. The refusal is a 403 and not a 401:
      the caller authenticated, and then was not allowed.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, EpicPlanningIT.class})
  @Order(1)
  void aPersonIsRefusedTheMachineOnlyDoors(Interactions story) {
    NetworkCapture.actor(ADMIN);
    String projectId = StoryPlatform.projectId();

    StoryIdentities.person(given(), "alice")
        .contentType(ContentType.JSON)
        .body(
            """
            {"repositoryId":"%s","name":"%s","archetype":"SERVICE"}
            """
                .formatted(StoryPlatform.SEEDED_REPO_ID, StoryPlatform.SEEDED_REPO_NAME))
        .when()
        .post(StoryTarget.adoptPath(projectId))
        .then()
        .statusCode(403);
    story
        .note("an administrator's session is refused the bootstrap's adopt door")
        .as("adopt-refused");

    StoryIdentities.person(given(), "alice")
        .when()
        .get(StoryTarget.byNamePath(projectId, StoryPlatform.COMPONENT_NAME))
        .then()
        .statusCode(403);
    story
        .note("…and the git host's name resolution, which is the same kind of door")
        .as("resolution-refused");

    // …and this is what makes the two above a refusal of a ROUTE rather than of a caller: the same
    // session, the same moment, a route that names qits:admin beside qits:system.
    StoryIdentities.person(given(), "alice")
        .when()
        .get(StoryTarget.PROJECTS_PATH)
        .then()
        .statusCode(200);
    story
        .note("the same session opens the projects overview, which names both roles")
        .as("overview-served");
  }

  @UserStory(value = "A platform service's bearer never rewrites the plan", category = CATEGORY)
  @UserStoryDescription(
      """
      The mirror image. A sibling service holds qits:system and reads this service constantly —
      qits-ci enumerates the repository catalogue, qits-workspaces looks a repository up by id —
      but the planning surface is a person's judgement about scope, so every epics, features and
      tasks route is qits:admin and nothing else. A perfectly valid machine bearer, minted against
      the same signing keys this service fetched at startup, is 403 on both a create and an update
      there, and 200 on the catalogue in the same breath.
      """)
  @Order(2)
  void aMachineIsRefusedThePlanningSurface(Interactions story) {
    NetworkCapture.actor(PLATFORM);
    platformBearer = StoryIdentities.platformToken("qits-ci");
    String projectId = StoryPlatform.projectId();

    given()
        .header("Authorization", "Bearer " + platformBearer)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"An epic no machine may propose\"}")
        .when()
        .post(StoryTarget.projectEpicsPath(projectId))
        .then()
        .statusCode(403);
    story.note("a machine bearer cannot propose an epic").as("epic-refused");

    // A task id that names nothing: the role check runs before the resource method, so the answer
    // is 403 rather than 404 — which is the right order, since a refused caller must not learn
    // which ids exist.
    given()
        .header("Authorization", "Bearer " + platformBearer)
        .contentType(ContentType.JSON)
        .body("{\"implementedAt\":\"2026-08-29T00:00:00Z\"}")
        .when()
        .put(StoryTarget.taskPath(UUID.randomUUID().toString()))
        .then()
        .statusCode(403);
    story
        .note("…nor mark a task implemented, and the refusal comes before the lookup")
        .as("task-refused");

    // The same bearer on the route it exists for. qits-ci reads this catalogue with exactly it.
    given()
        .header("Authorization", "Bearer " + platformBearer)
        .when()
        .get(StoryTarget.REPOSITORIES_PATH)
        .then()
        .statusCode(200);
    story.note("the same bearer reads the repository catalogue it exists for").as("catalogue-served");
  }

  @UserStory(value = "An unauthenticated caller reaches nothing at all", category = CATEGORY)
  @UserStoryDescription(
      """
      Nothing on this surface is open. Every controller carries a class-level role, so a request
      with neither a bearer nor the edge's forwarded headers is anonymous — and anonymous is a 401
      at the mechanism's challenge, not a 403, because the credential never became an identity and
      there is no caller to have been forbidden. Reads and writes answer alike: this service does
      not have a public half.
      """)
  @Order(3)
  void anAnonymousCallerIsChallenged(Interactions story) {
    NetworkCapture.actor(ANONYMOUS);

    given().when().get(StoryTarget.PROJECTS_PATH).then().statusCode(401);
    given().when().get(StoryTarget.REPOSITORIES_PATH).then().statusCode(401);
    story
        .note("neither the projects overview nor the repository catalogue answers an anonymous read")
        .as("reads-challenged");

    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"name":"A project nobody signed in to create","dns":{"domain":"anonymous.test.eu",
             "type":"A","value":"203.0.113.9"}}
            """)
        .when()
        .post(StoryTarget.PROJECTS_PATH)
        .then()
        .statusCode(401);
    story.note("and a write is challenged before it is ever validated").as("write-challenged");
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the person at the machine's doors ------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, PERSON_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, PERSON_SLUG, "adopt-refused");
    ReportAssertions.assertStepId(CATEGORY, PERSON_SLUG, "resolution-refused");
    ReportAssertions.assertStepId(CATEGORY, PERSON_SLUG, "overview-served");
    ReportAssertions.assertEdge(
        CATEGORY,
        PERSON_SLUG,
        NetworkEdge.HTTP,
        ADMIN,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.adoptPath("{id}") + " -> 403");
    ReportAssertions.assertEdge(
        CATEGORY,
        PERSON_SLUG,
        NetworkEdge.HTTP,
        ADMIN,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.byNamePath("{id}", StoryPlatform.COMPONENT_NAME) + " -> 403");
    ReportAssertions.assertEdge(
        CATEGORY,
        PERSON_SLUG,
        NetworkEdge.HTTP,
        ADMIN,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.PROJECTS_PATH + " -> 200");
    // The claim a presence check cannot make: a refusal is decided at the door, so this service
    // did no work on the caller's behalf — nothing reached the git host.
    ReportAssertions.assertNoEdgesTo(CATEGORY, PERSON_SLUG, StoryGitHost.SERVICE_NAME);

    // --- the machine at the person's doors ------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, MACHINE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, MACHINE_SLUG, "epic-refused");
    ReportAssertions.assertStepId(CATEGORY, MACHINE_SLUG, "task-refused");
    ReportAssertions.assertStepId(CATEGORY, MACHINE_SLUG, "catalogue-served");
    ReportAssertions.assertEdge(
        CATEGORY,
        MACHINE_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.projectEpicsPath("{id}") + " -> 403");
    ReportAssertions.assertEdge(
        CATEGORY,
        MACHINE_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.taskPath("{id}") + " -> 403");
    ReportAssertions.assertEdge(
        CATEGORY,
        MACHINE_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.REPOSITORIES_PATH + " -> 200");
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, MACHINE_SLUG, List.of(PLATFORM));
    ReportAssertions.assertNoEdgesTo(CATEGORY, MACHINE_SLUG, StoryGitHost.SERVICE_NAME);
    // A story that authenticates holds a credential, and a report is a document somebody publishes:
    // the diagram carries the door and the status, and never the key.
    assertNotNull(platformBearer, "the machine story must have minted a bearer");
    assertFalse(platformBearer.isBlank());
    ReportAssertions.assertNotLeaked(CATEGORY, MACHINE_SLUG, platformBearer);

    // --- nobody at all --------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, ANONYMOUS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, ANONYMOUS_SLUG, "reads-challenged");
    ReportAssertions.assertStepId(CATEGORY, ANONYMOUS_SLUG, "write-challenged");
    ReportAssertions.assertEdge(
        CATEGORY,
        ANONYMOUS_SLUG,
        NetworkEdge.HTTP,
        ANONYMOUS,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.PROJECTS_PATH + " -> 401");
    ReportAssertions.assertEdge(
        CATEGORY,
        ANONYMOUS_SLUG,
        NetworkEdge.HTTP,
        ANONYMOUS,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.REPOSITORIES_PATH + " -> 401");
    ReportAssertions.assertEdge(
        CATEGORY,
        ANONYMOUS_SLUG,
        NetworkEdge.HTTP,
        ANONYMOUS,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.PROJECTS_PATH + " -> 401");
    // Exactly three arrows and one initiator: an anonymous caller moved nothing anywhere.
    ReportAssertions.assertEdgeCount(CATEGORY, ANONYMOUS_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, ANONYMOUS_SLUG, List.of(ANONYMOUS));
    ReportAssertions.assertNoEdgesTo(CATEGORY, ANONYMOUS_SLUG, StoryGitHost.SERVICE_NAME);
  }
}
