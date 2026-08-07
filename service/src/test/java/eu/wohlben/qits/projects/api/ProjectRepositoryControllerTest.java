package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * The repository surface of a project over HTTP: creating a component either way, the wrapper block
 * the UI reads drift from, the reconcile action, and the membership guard's refusals.
 *
 * <p>The wrapper here is created greenfield, so it starts with no {@code .gitmodules} — the state
 * every project is in before it declares its first component. That is deliberate: it is what makes
 * the "empty manifest stands down" rule visible beside the enforcement.
 */
@QuarkusTest
public class ProjectRepositoryControllerTest {

  private final String fixtureUrl;

  public ProjectRepositoryControllerTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private io.restassured.response.Response postRepository(
      String projectId, String url, String name, RepositoryArchetype archetype) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(url, name, archetype))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories");
  }

  // --- create: blank ---

  @Test
  public void creatingABlankRepositoryMountsItUnderItsArchetypesDirectory() {
    String projectId = createProject("Blank Create");

    postRepository(projectId, null, "checkout", RepositoryArchetype.SERVICE)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", notNullValue())
        .body("repository.name", equalTo("checkout"))
        // A greenfield wrapper names no forge, so there is no twin to derive yet.
        .body("repository.backupUrl", nullValue())
        .body("repository.mainBranch", equalTo("main"))
        .body("repository.archetype", equalTo("SERVICE"))
        .body("projectId", equalTo(projectId))
        .body("wrapperPath", equalTo("services/checkout"));

    // And the wrapper block now declares it, which is what the UI reads.
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("wrapper.branch", equalTo("main"))
        .body("wrapper.entries", hasSize(1))
        .body("wrapper.entries[0].path", equalTo("services/checkout"))
        .body("wrapper.entries[0].name", equalTo("checkout"))
        .body("wrapper.entries[0].repositoryId", notNullValue())
        .body("entries.repository.name", hasItem("checkout"));
  }

  @Test
  public void aBlankRepositoryNameMustBeFreeAndGitSafe() {
    String projectId = createProject("Blank Names");
    postRepository(projectId, null, "taken", RepositoryArchetype.LIBRARY)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    postRepository(projectId, null, "taken", RepositoryArchetype.LIBRARY)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("already taken"));
    postRepository(projectId, null, "has/slash", RepositoryArchetype.LIBRARY)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    postRepository(projectId, null, "-dashfirst", RepositoryArchetype.LIBRARY)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  // --- create: attach ---

  @Test
  public void attachingAnExistingRepositoryAlsoJoinsTheWrapper() {
    String projectId = createProject("Attach Create");

    postRepository(projectId, fixtureUrl, null, RepositoryArchetype.FRONTEND)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.name", equalTo("testing-repo"))
        .body("repository.backupUrl", equalTo(fixtureUrl))
        .body("wrapperPath", equalTo("frontends/testing-repo"));
  }

  // --- create: the request's own rules ---

  @Test
  public void exactlyOneOfUrlAndNameIsRequired() {
    String projectId = createProject("Xor");

    postRepository(projectId, null, null, RepositoryArchetype.SERVICE)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("exactly one"));
    postRepository(projectId, fixtureUrl, "both", RepositoryArchetype.SERVICE)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("exactly one"));
  }

  @Test
  public void anUnplaceableArchetypeIsRejected() {
    String projectId = createProject("Unplaceable");

    postRepository(projectId, fixtureUrl, null, RepositoryArchetype.FORK)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("no directory in the wrapper"));
    postRepository(projectId, fixtureUrl, null, RepositoryArchetype.SERVICE_TEMPLATE)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    postRepository(projectId, fixtureUrl, null, null)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  // --- reconcile ---

  @Test
  public void reconcileAnswersWithWhatItCameTo() {
    String projectId = createProject("Reconcile Endpoint");
    postRepository(projectId, null, "worker", RepositoryArchetype.DAEMON)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("projectId", equalTo(projectId))
        .body("branch", equalTo("main"))
        .body("wrapperRepositoryId", notNullValue())
        .body("entries", hasSize(1))
        .body("entries[0].path", equalTo("daemons/worker"))
        .body("entries[0].outcome", equalTo("KEPT"));
  }

  @Test
  public void reconcileIsA404ForAnUnknownProject() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/no-such-project/repositories/reconcile")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  // --- membership ---

  /**
   * A wrapper with no entries is not a manifest, so nothing is enforced against it — the state every
   * project is in until it declares its first component, and the reason this guard does not brick
   * every project the day it ships.
   */
  @Test
  public void withAnEmptyWrapperTheWritePathsStayOpen() {
    String projectId = createProject("Membership Open");
    String strayId = registerStray(projectId);

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/repositories/" + strayId + "/pull")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  /**
   * Once the wrapper declares components, a repository it does not name is not part of the project
   * and cannot be written to. Reads stay open, because seeing a stray repository is how you find out
   * it is one.
   */
  @Test
  public void aStrayRepositoryCannotBePulledPushedSyncedOrHaveBranchesDeleted() {
    String projectId = createProject("Membership Refusal");
    postRepository(projectId, null, "declared", RepositoryArchetype.SERVICE)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    String strayId = registerStray(projectId);

    for (String verb : new String[] {"pull", "push", "sync"}) {
      given()
          .contentType(ContentType.JSON)
          .when()
          .post("/projects/api/repositories/" + strayId + "/" + verb)
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
          .body("message", containsString("not a submodule of this project\'s wrapper"));
    }
    given()
        .when()
        .delete("/projects/api/repositories/" + strayId + "/branches?branch=feature")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    given()
        .when()
        .get("/projects/api/repositories/" + strayId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  /** Deleting a member takes it out of the wrapper first, so the manifest never names a ghost. */
  @Test
  public void deletingAMemberRemovesItsWrapperEntry() {
    String projectId = createProject("Membership Delete");
    postRepository(projectId, null, "keeper", RepositoryArchetype.LIBRARY)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    String goingId =
        postRepository(projectId, null, "going", RepositoryArchetype.SERVICE)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    given()
        .when()
        .delete("/projects/api/repositories/" + goingId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .body("wrapper.entries", hasSize(1))
        .body("wrapper.entries[0].name", equalTo("keeper"));
  }

  // --- backup triggers ---

  /**
   * The button beside a red backup status. 202 and not 200: the answer is "queued", and what it came
   * to lands on the repository's own {@code lastBackup} rather than in this response.
   */
  @Test
  public void aRepositoryCanBeAskedToBackItselfUpNow() {
    String projectId = createProject("Backup Trigger One");
    String repoId =
        postRepository(projectId, fixtureUrl, null, RepositoryArchetype.SERVICE)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/repositories/" + repoId + "/backup-sync")
        .then()
        .statusCode(Response.Status.ACCEPTED.getStatusCode())
        .body("repositoryId", equalTo(repoId))
        .body("scheduled", equalTo(true));
  }

  /** An impatient second click folds into the first run rather than starting a second push. */
  @Test
  public void repeatedTriggersAreAcceptedAndCollapse() {
    String projectId = createProject("Backup Trigger Burst");
    String repoId =
        postRepository(projectId, fixtureUrl, null, RepositoryArchetype.SERVICE)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    for (int i = 0; i < 5; i++) {
      given()
          .contentType(ContentType.JSON)
          .when()
          .post("/projects/api/repositories/" + repoId + "/backup-sync")
          .then()
          .statusCode(Response.Status.ACCEPTED.getStatusCode());
    }
  }

  @Test
  public void triggeringAnUnknownRepositoryIsA404() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/repositories/no-such-repository/backup-sync")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /**
   * The project-wide form, for the case a sign-in has just fixed the credentials every repository
   * was failing on. The count is what was scheduled, so a row with no twin is not in it.
   */
  @Test
  public void aWholeProjectCanBeAskedToBackItselfUp() {
    String projectId = createProject("Backup Trigger All");
    postRepository(projectId, fixtureUrl, null, RepositoryArchetype.SERVICE)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/backup-sync")
        .then()
        .statusCode(Response.Status.ACCEPTED.getStatusCode())
        .body("projectId", equalTo(projectId))
        // The attached repository has a twin; the greenfield wrapper does not, so it is not counted.
        .body("scheduled", equalTo(1));
  }

  @Test
  public void aProjectWithNothingToBackUpSchedulesNothing() {
    String projectId = createProject("Backup Trigger Empty");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/backup-sync")
        .then()
        .statusCode(Response.Status.ACCEPTED.getStatusCode())
        .body("scheduled", equalTo(0));
  }

  @Test
  public void triggeringAnUnknownProjectIsA404() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/no-such-project/repositories/backup-sync")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /** Never attempted is not a status, and the DTO says so by leaving the block off entirely. */
  @Test
  public void aFreshRepositoryReportsNoBackupYet() {
    String projectId = createProject("Backup Dto Shape");
    postRepository(projectId, null, "untouched", RepositoryArchetype.LIBRARY)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.lastBackup", nullValue());
  }

  /**
   * A repository under the project that the wrapper does not declare. Registered through the domain
   * service rather than the create route, because the create route is precisely what would also add
   * the wrapper entry — this is the drift a hand-edited wrapper (or a failed wrapper commit) leaves.
   */
  private String registerStray(String projectId) {
    return strayRegistrar.register(projectId, fixtureUrl);
  }

  @jakarta.inject.Inject StrayRegistrar strayRegistrar;

  /** See {@link #registerStray}. */
  @jakarta.enterprise.context.ApplicationScoped
  public static class StrayRegistrar {
    @jakarta.inject.Inject eu.wohlben.qits.projects.control.ProjectService projectService;

    public String register(String projectId, String url) {
      return projectService
          .createRepositoryUnderProject(projectId, url, RepositoryArchetype.LIBRARY)
          .id;
    }
  }
}
