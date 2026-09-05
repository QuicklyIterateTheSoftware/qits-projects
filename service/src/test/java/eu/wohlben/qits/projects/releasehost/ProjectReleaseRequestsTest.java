package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The project-wide release-request list: every repository of one project in one read, defaulting to
 * the requests that can still move plus the handful that have just landed.
 *
 * <p>Requests are made through the real repository-scoped route, so what this asserts about scoping
 * rests on the {@code projectId} the creation path actually denormalises onto the row rather than on
 * a hand-written column. The gate is held open ({@link FakeActiveBuilds} answering "a run is still
 * active") for the same reason: this is a test about a list, and a request that released itself
 * mid-seed would make the fixture depend on the state machine's timing.
 */
@QuarkusTest
public class ProjectReleaseRequestsTest {

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  /**
   * Reset because the fakes are application-scoped and a neighbouring class scripts them: a merger
   * left answering a conflict would make every fixture here CONFLICTED for reasons nothing in this
   * class states.
   */
  @Inject RecordingBackingBranchMerger merger;

  private String projectId;
  private String otherProjectId;
  private String serviceRepoId;
  private String frontendRepoId;
  private String otherRepoId;

  @BeforeEach
  void seed() {
    executor.reset();
    merger.reset();
    // Nothing must release itself while the fixture is being built: the list is the subject.
    activeBuilds.answer(Optional.of(1));
    projectId = "prr-project-" + UUID.randomUUID();
    otherProjectId = "prr-other-project-" + UUID.randomUUID();
    serviceRepoId = "prr-service-" + UUID.randomUUID();
    frontendRepoId = "prr-frontend-" + UUID.randomUUID();
    otherRepoId = "prr-other-repo-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = project(projectId, "prr");
              namedRepository(project, serviceRepoId, "prr-service");
              namedRepository(project, frontendRepoId, "prr-frontend");
              Project other = project(otherProjectId, "prr-other");
              namedRepository(other, otherRepoId, "prr-other-repo");
            });
  }

  /**
   * <b>Open requests must not outlive this class.</b> {@code ReleaseRequests.sweep()} has no scope —
   * it walks every open row in the database — so a fixture left PENDING here is a door call inside
   * the next test that sweeps, and {@code ReleaseRequestFlowTest} counts those calls. Leaving the
   * project and its repositories behind is harmless; leaving work behind is not.
   */
  @AfterEach
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequest.delete(
                    "projectId in ?1", List.of(projectId, otherProjectId)));
  }

  private static Project project(String id, String prefix) {
    Project project = new Project();
    project.id = id;
    project.name = prefix;
    project.slug = prefix + "-" + UUID.randomUUID();
    project.persist();
    return project;
  }

  private static void namedRepository(Project project, String repoId, String name) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.persist();
    RepositoryName alias = new RepositoryName();
    alias.project = project;
    alias.repository = repository;
    alias.name = name;
    alias.persist();
  }

  private static String repoBase(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String projectBase() {
    return "/projects/api/projects/" + projectId + "/release-requests";
  }

  private static String create(String repoId, String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a gated release of " + branch + "\"}")
        .post(repoBase(repoId))
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private static void withdraw(String repoId, String requestId) {
    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"moot\"}")
        .post(repoBase(repoId) + "/" + requestId + "/withdraw")
        .then()
        .statusCode(200);
  }

  /**
   * A landed release, written straight to the table. The state machine's own way there is asserted
   * in {@code ReleaseRequestFlowTest}; what this class is about is the reading, and eleven releases
   * driven through the gate would be eleven door calls to make one list.
   */
  private String released(String repoId, String summary, Instant when) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row = new ReleaseRequest();
              row.id = UUID.randomUUID().toString();
              row.repoId = repoId;
              row.projectId = projectId;
              row.summary = summary;
              row.state = ReleaseRequest.State.RELEASED;
              row.version = "2026.903." + Math.abs(summary.hashCode() % 900000 + 100000);
              row.createdAt = when;
              row.armedAt = when;
              row.updatedAt = when;
              row.persist();
              return row.id;
            });
  }

  private List<String> idsAt(String query) {
    return given().get(projectBase() + query).then().statusCode(200).extract().path("requests.id");
  }

  @Test
  public void theDefaultIsEveryOpenRequestInTheProjectAndNothingElse() {
    String service = create(serviceRepoId, "work-service");
    String frontend = create(frontendRepoId, "work-frontend");
    String elsewhere = create(otherRepoId, "work-elsewhere");
    String settled = create(serviceRepoId, "work-settled");
    withdraw(serviceRepoId, settled);

    List<String> ids = idsAt("");
    assertTrue(
        ids.containsAll(List.of(service, frontend)),
        "both repositories' open requests are in the project's list: " + ids);
    assertFalse(
        ids.contains(elsewhere), "another project's request is not this project's business");
    assertFalse(ids.contains(settled), "a withdrawn request is not open work");
    assertEquals(2, ids.size(), "exactly the two open ones");
  }

  /**
   * <b>The default is the open work plus the last ten releases</b>, and the tail is what makes the
   * page readable: a request that lands is no longer waiting on anybody, so it leaves the open set —
   * and a worklist that dropped it there and then made the one event people actually come to check
   * the one thing it never showed. Ten and not the history, so a project with a year of releases
   * costs the same read as one with three.
   */
  @Test
  public void theDefaultCarriesTheLastTenReleasesBehindTheOpenWork() {
    String open = create(serviceRepoId, "still-open");
    List<String> releases = new ArrayList<>();
    for (int index = 0; index < 11; index++) {
      // Oldest first, so the one that must fall off the tail is releases.get(0).
      releases.add(released(serviceRepoId, "release " + index, Instant.now().minusSeconds(600 - index)));
    }

    List<String> ids = idsAt("");

    assertEquals(11, ids.size(), "the open request and exactly ten releases: " + ids);
    assertEquals(open, ids.get(0), "the open request moved last, and the list is by what moved last");
    assertFalse(
        ids.contains(releases.get(0)),
        "the eleventh-oldest release is off the tail, which is what makes this a page");
    assertEquals(
        releases.subList(1, 11).reversed(),
        ids.subList(1, 11),
        "and the ten that are on it are most recently released first");
  }

  /** The tail is the DEFAULT's alone: a named state is a narrowing and never gets a tail bolted on. */
  @Test
  public void namingAStateAnswersThatStateAndNothingBesideIt() {
    create(serviceRepoId, "narrowing-open");
    String release = released(frontendRepoId, "a release", Instant.now());

    given()
        .get(projectBase() + "?state=RELEASED")
        .then()
        .statusCode(200)
        .body("requests.id", contains(release));
    given()
        .get(projectBase() + "?state=PENDING")
        .then()
        .statusCode(200)
        .body("requests.id", not(hasItem(release)));
  }

  @Test
  public void everyRowNamesItsRepository() {
    create(serviceRepoId, "named-service");
    create(frontendRepoId, "named-frontend");

    given()
        .get(projectBase())
        .then()
        .statusCode(200)
        .body("requests.repoName", hasItem("prr-service"))
        .body("requests.repoName", hasItem("prr-frontend"));
  }

  @Test
  public void aRenamedRepositoryIsNamedByTheAliasItAnswersToNow() {
    // The row's own repoName is the snapshot taken when the request was made; the list resolves the
    // alias table instead, so a rename between the ask and the read shows the name that works.
    String id = create(serviceRepoId, "renamed");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                RepositoryName.update(
                    "name = ?1 where repository.id = ?2", "prr-service-renamed", serviceRepoId));

    given()
        .get(projectBase() + "?state=all")
        .then()
        .statusCode(200)
        .body("requests.find { it.id == '" + id + "' }.repoName", equalTo("prr-service-renamed"));
  }

  @Test
  public void stateAllAnswersTheSettledOnesTooMostRecentlyMovedFirst() {
    String open = create(frontendRepoId, "all-open");
    String settled = create(serviceRepoId, "all-settled");
    withdraw(serviceRepoId, settled);

    // The withdrawal is the last thing that moved, so it leads — the list is a worklist, ordered by
    // when each request last changed rather than when it was first asked for.
    given()
        .get(projectBase() + "?state=all")
        .then()
        .statusCode(200)
        .body("requests[0].id", equalTo(settled))
        .body("requests.id", hasItem(open));
  }

  @Test
  public void oneStateNameNarrowsToIt() {
    String open = create(frontendRepoId, "narrow-open");
    String settled = create(serviceRepoId, "narrow-settled");
    withdraw(serviceRepoId, settled);

    given()
        .get(projectBase() + "?state=WITHDRAWN")
        .then()
        .statusCode(200)
        .body("requests.id", contains(settled))
        .body("requests.id", not(hasItem(open)));

    // Case is not part of the ask; a lowercase state name is the same state.
    given()
        .get(projectBase() + "?state=withdrawn")
        .then()
        .statusCode(200)
        .body("requests.id", contains(settled));

    given()
        .get(projectBase() + "?state=RELEASED")
        .then()
        .statusCode(200)
        .body("requests", hasSize(0));
  }

  @Test
  public void aStateNamingNothingIsARefusalThatSaysWhatWouldWork() {
    // A typo must never read as "nothing is pending here" — that is the one wrong answer this page
    // can give.
    given()
        .get(projectBase() + "?state=pendign")
        .then()
        .statusCode(400)
        .body("message", containsString("pendign"))
        .body("message", containsString("PENDING"))
        .body("message", containsString("all"));
  }

  @Test
  public void anUnknownProjectIsNotAnEmptyList() {
    given()
        .get("/projects/api/projects/no-such-project-" + UUID.randomUUID() + "/release-requests")
        .then()
        .statusCode(404);
  }
}
