package eu.wohlben.qits.projects.stories.catalogue;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.projects.api.GitHostFixture;
import eu.wohlben.qits.projects.api.TokenValidationBootstrapIT;
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
 * <b>How the platform's catalogue grows</b> — the three ways a repository comes to exist under a
 * project, in the order a platform actually meets them.
 *
 * <p>A project here is not a row with a name on it. Creating one <b>publishes a repository to the
 * git host</b> — the wrapper, {@code <slug>-<slug>}, whose {@code .gitmodules} is the project's
 * configuration — and every later component is the same statement made twice: a bare on the git
 * host, and a submodule entry committed into that wrapper. So every story here has two ends, and
 * only one of them is the API:
 *
 * <ul>
 *   <li>the <b>near</b> end is what the caller sent, drawn by the framework's RestAssured tap;
 *   <li>the <b>far</b> end is what qits-projects then did to the git host, drawn by {@link
 *       StoryGitHost} out of the git host's own access log — {@code PUT /git/{id}} to create the
 *       bare, then the smart-HTTP advertisement and pack that actually move a ref.
 * </ul>
 *
 * <p><b>The third story is the one that reaches least, and that is its point.</b> Adoption
 * registers a repository the git host <em>already serves</em> — the state qits-cli-bootstrap leaves
 * behind, since it creates every platform bare before this service exists to be asked. It asks the
 * host once, whether the bare is there and what its default branch is, and then writes rows.
 * Nothing is cloned, nothing is pushed, and no mirror is created: fetching into a history the CI
 * host has already built from would risk rewinding refs. {@code assertEdgeCount} is what says that,
 * and no presence check could.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(GitHostFixture.class)
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProjectCatalogueIT {

  static final String CATEGORY = "catalogue";

  static final String CREATED_SLUG = "a-project-is-created-and-its-wrapper-published-to-the-git-host";
  static final String COMPONENT_SLUG = "a-component-joins-the-project-and-the-wrapper-names-it";
  static final String ADOPTED_SLUG = "the-bootstrap-registers-a-repository-the-git-host-already-serves";

  /** The person who owns the shape of the project. */
  static final String PRODUCT_OWNER = "a product owner";

  /** The machine that created every platform bare before any row named one. */
  static final String BOOTSTRAP = "the platform bootstrap";

  /** The component this project's owner adds, and the directory its archetype mounts it under. */
  static final String COMPONENT_NAME = "checkout-service";

  static final String COMPONENT_ARCHETYPE = "SERVICE";

  static final String COMPONENT_PATH = "services/" + COMPONENT_NAME;

  /** The project this class creates in its first story and grows in the next two. */
  private static String projectId;

  private static String projectSlug;

  private static String wrapperRepositoryId;

  /** Kept so {@code @AfterAll} can assert the bootstrap's bearer never reached the bundle. */
  private static String bootstrapBearer;

  /**
   * The inbound tap, once — the framework's own, idempotent per service.
   */
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

  @UserStory(
      value = "A project is created and its wrapper published to the git host",
      category = CATEGORY)
  @UserStoryDescription(
      """
      Creating a project is not a row with a name on it. The wrapper repository — <slug>-<slug>,
      whose .gitmodules is the project's configuration — is created on the platform's git host and
      seeded with the project template's skeleton commit in the same request, so the answer already
      carries a wrapper id. The arrows into the git host are the whole of publishing one: create
      the bare, ask receive-pack what it holds, send the pack, and then read the advertisement back
      to check the ref landed where it was meant to. Reading the project's components afterwards
      shows exactly that one repository.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aProjectIsCreatedAndItsWrapperPublished(Interactions story) {
    NetworkCapture.actor(PRODUCT_OWNER);
    // Run-unique, and DELIBERATELY SHORT: the wrapper is named <slug>-<slug> and a repository name
    // is one path segment on the git host, capped at 64 characters — a full nanosecond stamp on
    // both halves would be refused with a message about the name rather than about the stamp.
    projectSlug = "story-checkout-" + System.nanoTime() % 100_000_000L;

    JsonPath created =
        StoryIdentities.person(given(), "priya")
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "name",
                    "Story Checkout",
                    "slug",
                    projectSlug,
                    "description",
                    "The project this catalogue story grows.",
                    "dns",
                    Map.of("domain", "story-checkout.test.eu", "type", "A", "value", "203.0.113.11")))
            .when()
            .post(StoryTarget.PROJECTS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    projectId = created.getString("project.id");
    wrapperRepositoryId = created.getString("wrapper.id");
    assertNotNull(projectId, "creation must answer with the project it made");
    assertEquals(
        projectSlug, created.getString("project.slug"), "a SUPPLIED slug is a statement, not a hint");
    assertNotNull(wrapperRepositoryId, "creation is not finished until the wrapper exists");
    // The dns record is required at creation and is read back through the embeddable — a project is
    // a deployable application, and a deployable application has an address.
    assertEquals("story-checkout.test.eu", created.getString("project.dns.domain"));
    story
        .note("a project is created, and the answer already carries the wrapper repository")
        .as("project-created");

    // The far side has to have finished before this story returns, or its pack lands in the next
    // story's diagram. It is synchronous inside the request, so this is a guard on the last bytes.
    StoryGitHost.awaitRead(StoryGitHost.receivePackPath(wrapperRepositoryId));

    List<Map<String, Object>> entries =
        StoryIdentities.person(given(), "priya")
            .when()
            .get(StoryTarget.projectRepositoriesPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entries");
    assertEquals(1, entries.size(), "a fresh project holds exactly its wrapper");
    Map<?, ?> wrapper = (Map<?, ?>) entries.getFirst().get("repository");
    assertEquals(wrapperRepositoryId, wrapper.get("id"));
    assertEquals("PROJECT", wrapper.get("archetype"), "the wrapper's archetype names what it is");
    assertEquals(
        projectSlug + "-" + projectSlug,
        wrapper.get("name"),
        "the wrapper is addressable as <slug>-<slug> exactly — a committed ../<name>.git depends on it");
    story
        .note("the project's component list holds its wrapper and nothing else yet")
        .as("wrapper-listed");
  }

  @UserStory(value = "A component joins the project and the wrapper names it", category = CATEGORY)
  @UserStoryDescription(
      """
      Adding a component to a project is one statement made twice: a bare repository on the git
      host, seeded from the repository template, and a submodule entry committed into the
      project's wrapper under the directory its archetype names. The two together are what makes
      the repository part of the project — a row the wrapper does not name is reported UNDECLARED
      and is not a member — so the listing that comes back marks the new component declared and
      the wrapper's own manifest names its path.
      """)
  @Order(2)
  void aComponentJoinsTheProject(Interactions story) {
    NetworkCapture.actor(PRODUCT_OWNER);
    assertNotNull(projectId, "the story that created the project must have run first");

    JsonPath added =
        StoryIdentities.person(given(), "priya")
            .contentType(ContentType.JSON)
            .body(Map.of("name", COMPONENT_NAME, "archetype", COMPONENT_ARCHETYPE))
            .when()
            .post(StoryTarget.projectRepositoriesPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    String componentId = added.getString("repository.id");
    assertNotNull(componentId);
    assertEquals(COMPONENT_NAME, added.getString("repository.name"));
    assertEquals(
        COMPONENT_PATH,
        added.getString("wrapperPath"),
        "the archetype decides the directory, and the directory is where the wrapper mounts it");
    story
        .note("a blank component is created on the git host and mounted into the wrapper")
        .as("component-created");

    StoryGitHost.awaitRead(StoryGitHost.receivePackPath(componentId));

    JsonPath listing =
        StoryIdentities.person(given(), "priya")
            .when()
            .get(StoryTarget.projectRepositoriesPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    List<Map<String, Object>> entries = listing.getList("entries");
    assertEquals(2, entries.size(), "the wrapper and the component it now names");
    Map<String, Object> component =
        entries.stream()
            .filter(entry -> componentId.equals(((Map<?, ?>) entry.get("repository")).get("id")))
            .findFirst()
            .orElseGet(() -> fail("the new component is not in the project's listing"));
    assertEquals(
        Boolean.TRUE,
        component.get("declared"),
        "declared is the wrapper's .gitmodules naming it — membership, not existence");
    assertTrue(
        listing.getList("wrapper.entries.path", String.class).contains(COMPONENT_PATH),
        "the wrapper's own manifest names the path the component was mounted at");
    story
        .note("the wrapper's manifest names the component, so the project declares it")
        .as("component-declared");
  }

  @UserStory(
      value = "The bootstrap registers a repository the git host already serves",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The third way a repository comes to exist here, and the only one where this service did not
      put it on the git host. qits-cli-bootstrap creates a bare for every platform deployable
      directly on the git host, so those repositories are real, pushed to and building with no row
      here at all until adoption gives them one. The caller supplies both coordinates — the opaque
      storage id it minted and the public name — and holds qits:system, because a person has no
      storage id to supply. qits-projects asks the git host once, whether that bare is there and
      what branch it publishes, and writes the row and its alias. Nothing is cloned, nothing is
      pushed, and no mirror is made: fetching into a history the CI host has already built from
      would risk rewinding refs somebody has released against.
      """)
  @Order(3)
  void theBootstrapAdoptsAnExistingRepository(Interactions story) {
    NetworkCapture.actor(BOOTSTRAP);
    assertNotNull(projectId, "the story that created the project must have run first");
    bootstrapBearer = StoryIdentities.platformToken("qits-cli-bootstrap");

    JsonPath adopted =
        given()
            .header("Authorization", "Bearer " + bootstrapBearer)
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "repositoryId",
                    StoryPlatform.SEEDED_REPO_ID,
                    "name",
                    StoryPlatform.SEEDED_REPO_NAME,
                    "archetype",
                    "SERVICE"))
            .when()
            .post(StoryTarget.adoptPath(projectId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        StoryPlatform.SEEDED_REPO_ID,
        adopted.getString("repository.id"),
        "the row takes the storage id the git host already holds the bare under");
    assertEquals(StoryPlatform.SEEDED_REPO_NAME, adopted.getString("repository.name"));
    assertEquals(
        StoryPlatform.DEFAULT_BRANCH,
        adopted.getString("repository.mainBranch"),
        "the branch is READ from the host rather than assumed — a convention is not a fact");
    story
        .note("a repository the git host already serves becomes a component of the project")
        .as("repository-adopted");

    // …and now it resolves, which is the whole point: the alias is the only resolution path there
    // is, and this route is what turns /git/<project>/<name> into a storage id for qits-githost.
    assertEquals(
        StoryPlatform.SEEDED_REPO_ID,
        given()
            .header("Authorization", "Bearer " + StoryIdentities.platformToken("qits-githost"))
            .when()
            .get(StoryTarget.byNamePath(projectId, StoryPlatform.SEEDED_REPO_NAME))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("repositoryId"),
        "the name the adoption registered resolves to the id it registered it for");
    story
        .note("qits-githost can now turn the public name into the storage id it serves")
        .as("name-resolves");

    StoryGitHost.awaitRead(StoryGitHost.repoPath(StoryPlatform.SEEDED_REPO_ID));
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the project, and the wrapper it published ----------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, CREATED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, CREATED_SLUG, "project-created");
    ReportAssertions.assertStepId(CATEGORY, CREATED_SLUG, "wrapper-listed");
    ReportAssertions.assertEdge(
        CATEGORY,
        CREATED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.PROJECTS_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        CREATED_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.projectRepositoriesPath("{id}") + " -> 200");
    // The far side: the bare is created, then the skeleton is really pushed onto it. receive-pack
    // is the sole writer of every ref on this platform, which is why the push is in the diagram
    // rather than a file write nobody could see.
    ReportAssertions.assertEdge(
        CATEGORY,
        CREATED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("PUT", StoryGitHost.repoPath("{id}"), 201));
    ReportAssertions.assertEdge(
        CATEGORY,
        CREATED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", StoryGitHost.pushAdvertisementPath("{id}"), 200));
    ReportAssertions.assertEdge(
        CATEGORY,
        CREATED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("POST", StoryGitHost.receivePackPath("{id}"), 200));
    // Two initiators and no third: the person who asked, and the service acting for them.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, CREATED_SLUG, List.of(PRODUCT_OWNER, StoryTarget.SERVICE));

    // --- the component --------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, COMPONENT_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, COMPONENT_SLUG, "component-created");
    ReportAssertions.assertStepId(CATEGORY, COMPONENT_SLUG, "component-declared");
    ReportAssertions.assertEdge(
        CATEGORY,
        COMPONENT_SLUG,
        NetworkEdge.HTTP,
        PRODUCT_OWNER,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.projectRepositoriesPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        COMPONENT_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("PUT", StoryGitHost.repoPath("{id}"), 201));
    ReportAssertions.assertEdge(
        CATEGORY,
        COMPONENT_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("POST", StoryGitHost.receivePackPath("{id}"), 200));
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, COMPONENT_SLUG, List.of(PRODUCT_OWNER, StoryTarget.SERVICE));

    // --- the adoption ---------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, ADOPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, ADOPTED_SLUG, "repository-adopted");
    ReportAssertions.assertStepId(CATEGORY, ADOPTED_SLUG, "name-resolves");
    ReportAssertions.assertEdge(
        CATEGORY,
        ADOPTED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.adoptPath("{id}") + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        ADOPTED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.byNamePath("{id}", StoryPlatform.SEEDED_REPO_NAME) + " -> 200");
    // The storage id survives scrubbing because the bootstrap CHOSE it — so this label says which
    // repository was adopted, where a minted UUID would have said {id} and nothing else.
    ReportAssertions.assertEdge(
        CATEGORY,
        ADOPTED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", StoryGitHost.repoPath(StoryPlatform.SEEDED_REPO_ID), 200));
    // Exactly three arrows: two doors and ONE question to the git host. Adoption clones nothing,
    // pushes nothing and makes no mirror, and only a count can say so.
    ReportAssertions.assertEdgeCount(CATEGORY, ADOPTED_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, ADOPTED_SLUG, List.of(BOOTSTRAP, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, ADOPTED_SLUG, bootstrapBearer);
  }
}
