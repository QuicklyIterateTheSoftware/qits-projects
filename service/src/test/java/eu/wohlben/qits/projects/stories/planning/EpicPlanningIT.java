package eu.wohlben.qits.projects.stories.planning;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.GitHostFixture;
import eu.wohlben.qits.projects.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.projects.stories.catalogue.ProjectCatalogueIT;
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
 * <b>The planning surface</b> — an epic proposed, its scope frozen, and then its work marked done.
 *
 * <p>This is the half of qits-projects the refinement agents and the epics board live on, and its
 * one rule is worth stating before the stories: <b>the freeze is per field, not per endpoint</b>.
 * A structural change — the epic's title or description, and any feature or task create, update or
 * delete — needs the epic in {@code REFINING}; the implemented markers need it in {@code
 * IMPLEMENTATION}. So the same {@code PUT /tasks/{id}} is refused before the freeze and accepted
 * after it, depending on which field it carries, and the two stories below are exactly those two
 * sides.
 *
 * <p><b>Two actors, because it really is two people.</b> A product owner shapes and freezes the
 * scope; an engineer marks the work done afterwards and never touches the scope. The audit log is
 * what keeps them apart — every row records the {@code X-Qits-User} the edge asserted, which is the
 * only thing that ever produces a principal in a deployed process — and the second story reads it
 * back rather than taking the write's word for it.
 *
 * <p><b>The network claim is a negative one and it is the interesting one.</b> Nothing on this
 * surface leaves the process: no git host, no orchestrator, no idp. The plan is rows in this
 * service's own {@code epics} database, and {@code assertOnlyEdgesFrom} naming one person is what
 * says so. Contrast {@link ProjectCatalogueIT}, where every write publishes to the git host.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(GitHostFixture.class)
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EpicPlanningIT {

  static final String CATEGORY = "planning";

  static final String PROPOSED_SLUG = "a-plan-is-proposed-and-its-scope-frozen";
  static final String IMPLEMENTED_SLUG = "a-task-is-marked-implemented-and-the-audit-log-names-who";

  /** The person who shapes the scope and decides when it stops being a draft. */
  static final String PRODUCT_OWNER = "a product owner";

  /** The person who does the work and records that it is done. */
  static final String ENGINEER = "an engineer";

  /** The forwarded user names on the two identities — what {@code changed_by} ends up holding. */
  static final String OWNER_USER = "priya";

  static final String ENGINEER_USER = "morgan";

  /** When the work was finished, as the engineer records it. Authored, so no clock is in the story. */
  static final String IMPLEMENTED_AT = "2026-08-29T10:15:00Z";

  private static String epicId;

  private static String featureId;

  private static String taskId;

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

  @UserStory(value = "A plan is proposed and its scope frozen", category = CATEGORY)
  @UserStoryDescription(
      """
      A product owner drafts an epic against a project, gives it a feature, and gives that feature
      a task bound to one of the project's repositories — a task must name a repository in its own
      epic's project, so the plan and the catalogue are one graph rather than two. The epic starts
      REFINING, which is the only status in which any of that is allowed, and the transition door
      is the only thing that moves it: IMPLEMENTATION is the scope freeze. Afterwards the same
      person, on the same session, cannot add a second feature — 409, because the scope is no
      longer a draft, and that is a conflict rather than a permission problem.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, ProjectCatalogueIT.class})
  @Order(1)
  void aPlanIsProposedAndFrozen(Interactions story) {
    NetworkCapture.actor(PRODUCT_OWNER);
    String projectId = StoryPlatform.projectId();

    JsonPath epic =
        StoryIdentities.person(given(), OWNER_USER)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "title",
                    "Checkout hardening",
                    "description",
                    "Make the checkout path survive a payment provider outage."))
            .when()
            .post(StoryTarget.projectEpicsPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    epicId = epic.getString("epic.id");
    assertNotNull(epicId);
    assertEquals("REFINING", epic.getString("epic.status"), "a new epic is a draft, always");
    // startsWith rather than equals: a slug is unique within its project and takes the next free
    // -2, -3, … on a collision, so pinning the exact string would make this story a statement
    // about what else is in the database rather than about how a slug is minted.
    assertTrue(
        epic.getString("epic.slug").startsWith("checkout-hardening"),
        "the slug is minted from the title at create and never changes — branches are cut from it");
    assertNull(epic.getString("epic.supersededByEpicId"));
    story.note("a product owner proposes an epic; it starts as a draft").as("epic-proposed");

    JsonPath feature =
        StoryIdentities.person(given(), OWNER_USER)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "title",
                    "Retry the payment call",
                    "description",
                    "Hold a checkout through a provider that is briefly unreachable."))
            .when()
            .post(StoryTarget.epicFeaturesPath(epicId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    featureId = feature.getString("feature.id");
    assertNotNull(featureId);
    assertEquals(epicId, feature.getString("feature.epicId"));

    JsonPath task =
        StoryIdentities.person(given(), OWNER_USER)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "repositoryId",
                    StoryPlatform.componentRepositoryId(),
                    "title",
                    "Classify a provider timeout as retryable",
                    "description",
                    "A timeout is about the moment; a declined card is about the request."))
            .when()
            .post(StoryTarget.featureTasksPath(featureId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    taskId = task.getString("task.id");
    assertNotNull(taskId);
    assertEquals(
        StoryPlatform.componentRepositoryId(),
        task.getString("task.repositoryId"),
        "a task names the repository the work happens in, and it must be in this project");
    assertNull(task.getString("task.implementedAt"), "nothing is done yet");
    story
        .note("…a feature under it, and a task bound to one of the project's own repositories")
        .as("scope-drafted");

    JsonPath frozen =
        StoryIdentities.person(given(), OWNER_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("target", "IMPLEMENTATION"))
            .when()
            .post(StoryTarget.epicTransitionPath(epicId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals("IMPLEMENTATION", frozen.getString("epic.status"));
    assertNull(
        frozen.getMap("successor"),
        "only a supersede spawns a successor draft; a freeze spawns nothing");
    story.note("the scope is frozen: the epic moves to IMPLEMENTATION").as("scope-frozen");

    // …and the freeze bites, on the same session that just froze it. A structural write is 409 and
    // not 403: this caller is allowed, and the epic is not.
    StoryIdentities.person(given(), OWNER_USER)
        .contentType(ContentType.JSON)
        .body(Map.of("title", "A feature nobody may add now"))
        .when()
        .post(StoryTarget.epicFeaturesPath(epicId))
        .then()
        .statusCode(409);
    story
        .note("a second feature is refused with a conflict — the scope is no longer a draft")
        .as("freeze-enforced");
  }

  @UserStory(
      value = "A task is marked implemented and the audit log names who",
      category = CATEGORY)
  @UserStoryDescription(
      """
      With the scope frozen, the implemented markers open — the mirror image of the freeze, and on
      the very same route: PUT /tasks/{id} carrying implementedAt is accepted where the same route
      carrying a title is refused, because the guard is per field. An engineer marks the task done
      and then its feature, and "done" for the epic is derived from exactly that rather than
      stored, so no fifth status can disagree with it. The audit subtree is then read back to see
      who did it: every row records the X-Qits-User the platform edge asserted, which is the only
      thing that produces a principal in a deployed process at all.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void aTaskIsMarkedImplemented(Interactions story) {
    NetworkCapture.actor(ENGINEER);
    assertNotNull(taskId, "the story that drafted the plan must have run first");

    // The refusal first, so the contrast is in one story: the SAME route, a different field.
    StoryIdentities.person(given(), ENGINEER_USER)
        .contentType(ContentType.JSON)
        .body(Map.of("title", "A title nobody may change now"))
        .when()
        .put(StoryTarget.taskPath(taskId))
        .then()
        .statusCode(409);
    story
        .note("renaming the task is refused: its epic's scope is frozen")
        .as("scope-write-refused");

    JsonPath done =
        StoryIdentities.person(given(), ENGINEER_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("implementedAt", IMPLEMENTED_AT))
            .when()
            .put(StoryTarget.taskPath(taskId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertNotNull(
        done.getString("task.implementedAt"),
        "the same route, the marker field, accepted — the guard is per field");
    story.note("marking the task implemented is accepted on that same route").as("task-implemented");

    JsonPath shipped =
        StoryIdentities.person(given(), ENGINEER_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("implementedOn", IMPLEMENTED_AT))
            .when()
            .put(StoryTarget.featurePath(featureId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertNotNull(shipped.getString("feature.implementedOn"));
    story
        .note("and its feature is shipped — which is what makes the epic derivably done")
        .as("feature-shipped");

    // The audit subtree is queried by the epicId stamped on every row, so it holds the whole tree's
    // history — and it is what a reader comes back to months later.
    List<Map<String, Object>> audit =
        StoryIdentities.person(given(), ENGINEER_USER)
            .when()
            .get(StoryTarget.epicAuditPath(epicId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entries");
    assertTrue(audit.size() >= 2, "the draft and the marking are both in the history");
    assertTrue(
        audit.stream()
            .anyMatch(
                entry ->
                    "TASK".equals(entry.get("entityType"))
                        && taskId.equals(entry.get("entityId"))
                        && ENGINEER_USER.equals(entry.get("changedBy"))),
        "the engineer's forwarded name is on the row they changed");
    assertTrue(
        audit.stream()
            .anyMatch(
                entry ->
                    "EPIC".equals(entry.get("entityType"))
                        && OWNER_USER.equals(entry.get("changedBy"))),
        "…and the product owner's is still on the rows they created");
    story
        .note("the audit log names both people: who shaped the scope, and who finished the work")
        .as("audit-read");
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the plan -------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, PROPOSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, PROPOSED_SLUG, "epic-proposed");
    ReportAssertions.assertStepId(CATEGORY, PROPOSED_SLUG, "scope-drafted");
    ReportAssertions.assertStepId(CATEGORY, PROPOSED_SLUG, "scope-frozen");
    ReportAssertions.assertStepId(CATEGORY, PROPOSED_SLUG, "freeze-enforced");
    ReportAssertions.assertEdge(
        CATEGORY,
        PROPOSED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.projectEpicsPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PROPOSED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.epicFeaturesPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PROPOSED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.featureTasksPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PROPOSED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.epicTransitionPath("{id}") + " -> 200");
    // The refused write is its own arrow: same route, same actor, a different answer.
    ReportAssertions.assertEdge(
        CATEGORY,
        PROPOSED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.epicFeaturesPath("{id}") + " -> 409");
    // The claim: shaping a plan reaches nothing. One initiator, and no git host on the path — the
    // whole planning surface is rows in this service's own epics database.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, PROPOSED_SLUG, List.of(PRODUCT_OWNER));
    ReportAssertions.assertNoEdgesTo(CATEGORY, PROPOSED_SLUG, StoryGitHost.SERVICE_NAME);

    // --- the work -------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, IMPLEMENTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, IMPLEMENTED_SLUG, "scope-write-refused");
    ReportAssertions.assertStepId(CATEGORY, IMPLEMENTED_SLUG, "task-implemented");
    ReportAssertions.assertStepId(CATEGORY, IMPLEMENTED_SLUG, "feature-shipped");
    ReportAssertions.assertStepId(CATEGORY, IMPLEMENTED_SLUG, "audit-read");
    // The two answers of one route, drawn as two arrows — which is the per-field freeze, visible.
    ReportAssertions.assertEdge(
        CATEGORY,
        IMPLEMENTED_SLUG,
        NetworkEdge.HTTP,
        ENGINEER,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.taskPath("{id}") + " -> 409");
    ReportAssertions.assertEdge(
        CATEGORY,
        IMPLEMENTED_SLUG,
        NetworkEdge.HTTP,
        ENGINEER,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.taskPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        IMPLEMENTED_SLUG,
        NetworkEdge.HTTP,
        ENGINEER,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.featurePath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        IMPLEMENTED_SLUG,
        NetworkEdge.HTTP,
        ENGINEER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.epicAuditPath("{id}") + " -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, IMPLEMENTED_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, IMPLEMENTED_SLUG, List.of(ENGINEER));
    ReportAssertions.assertNoEdgesTo(CATEGORY, IMPLEMENTED_SLUG, StoryGitHost.SERVICE_NAME);
  }
}
