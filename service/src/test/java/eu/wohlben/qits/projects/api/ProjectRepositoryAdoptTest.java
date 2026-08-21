package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.projects.control.GitHostRepositories;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/projects/{projectId}/repositories/adopt} — how a caller that created a
 * bare on the git host itself registers it as a component of a project.
 *
 * <p><b>The bootstrap is that caller.</b> It creates every platform repository on the git host
 * minutes before qits-projects exists to be asked, so it holds both coordinates — the storage id it
 * used and the name the platform addresses the repository by — and this route is where it hands
 * them over. Without it the only registration path is the wrapper reconcile, which has to
 * rediscover a bare by asking the host for the entry name used as a storage id.
 *
 * <p>The dev-user fallback is blanked so the role matrix is the deployed posture: no header is
 * anonymous rather than the synthetic {@code dev} identity that holds every platform role. The
 * route is {@code qits:system} alone, so a browser session is refused here — which is what the
 * method-level annotation replacing the class-level one is for, and the defect class this repo
 * watches.
 */
@QuarkusTest
@TestProfile(ProjectRepositoryAdoptTest.DeployedPosture.class)
public class ProjectRepositoryAdoptTest {

  public static class DeployedPosture implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.auth.forward.dev-user", "");
    }
  }

  /** The fake git host of the domain suite: {@code ensure} is {@code PUT /git/<repoId>}. */
  @Inject GitHostRepositories gitHost;

  /** A browser session as the edge forwards it. */
  private RequestSpecification session() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin");
  }

  /** The bootstrap as it presents itself: a machine bearer's roles, and no browser session. */
  private RequestSpecification machine() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "dev-qits-bootstrap")
        .header("X-Qits-Roles", "qits:system");
  }

  private String createProject(String name) {
    return session()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  /** A repository the git host already serves, exactly as the bootstrap leaves one. */
  private String seededBare() {
    String repositoryId = "qits-" + UUID.randomUUID().toString().substring(0, 8);
    gitHost.ensure(repositoryId, "main");
    return repositoryId;
  }

  private io.restassured.response.Response adopt(
      RequestSpecification as, String projectId, String repositoryId, String name, String url) {
    return as.body(
            new ProjectController.AdoptProjectRepositoryRequest(
                repositoryId, name, url, RepositoryArchetype.SERVICE))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/adopt");
  }

  // --- the shape ---

  @Test
  public void adoptingTakesTheHostsStorageIdAndRegistersTheNameBesideIt() {
    String projectId = createProject("Adopt Shape");
    String repositoryId = seededBare();

    adopt(machine(), projectId, repositoryId, "qits-events", "https://forge.example/qits-events.git")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repositoryId))
        .body("repository.name", equalTo("qits-events"))
        .body("repository.archetype", equalTo("SERVICE"))
        .body("repository.mainBranch", equalTo("main"))
        .body("projectId", equalTo(projectId));

    // The point of the whole route: the name now resolves to that storage id, which is what
    // qits-githost asks on every name-addressed clone.
    machine()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories/by-name/qits-events")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositoryId", equalTo(repositoryId));
  }

  @Test
  public void adoptingTwiceAnswersTheRowItFound() {
    String projectId = createProject("Adopt Twice");
    String repositoryId = seededBare();

    adopt(machine(), projectId, repositoryId, "qits-ci", null)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repositoryId));

    // A rerun of the bootstrap makes this call again, for every repository, every time.
    adopt(machine(), projectId, repositoryId, "qits-ci", null)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repositoryId))
        .body("repository.name", equalTo("qits-ci"));
  }

  // --- the role matrix ---

  @Test
  public void aBrowserSessionIsRefusedAndAnonymousIsChallenged() {
    String projectId = createProject("Adopt Roles");
    String repositoryId = seededBare();

    // qits:admin opens every other route of this controller and deliberately not this one.
    adopt(session(), projectId, repositoryId, "qits-docs", null)
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.AdoptProjectRepositoryRequest(
                repositoryId, "qits-docs", null, RepositoryArchetype.SERVICE))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/adopt")
        .then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());

    // And the refusals changed nothing: the machine still gets the first registration.
    adopt(machine(), projectId, repositoryId, "qits-docs", null)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", notNullValue());
  }

  // --- the refusals that are about the coordinates ---

  @Test
  public void aStorageIdTheHostDoesNotHoldIs404() {
    String projectId = createProject("Adopt Unknown");

    adopt(machine(), projectId, "qits-never-created", "qits-never-created", null)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void theWrapperArchetypeIsRefused() {
    String projectId = createProject("Adopt Wrapper");
    String repositoryId = seededBare();

    machine()
        .body(
            new ProjectController.AdoptProjectRepositoryRequest(
                repositoryId, "qits-qits", null, RepositoryArchetype.PROJECT))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/adopt")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
