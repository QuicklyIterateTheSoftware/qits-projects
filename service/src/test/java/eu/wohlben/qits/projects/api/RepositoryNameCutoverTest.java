package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /projects/api/projects/{projectId}/repositories/by-name/{repoName}} holds through a
 * postgres cutover instead of turning one into a 404.
 *
 * <p><b>Why this route and not another.</b> It is qits-githost's 404 by proxy: the git host resolves
 * every name-addressed clone, fetch and push through it, and a git client treats "no such
 * repository" as an answer rather than as an outage — so a connection lost mid-read is remembered
 * long after the database is back. {@code RepositoryService#findByProjectAndName} is wrapped in
 * {@code DbRetry} for exactly that, and this is what says so.
 *
 * <p>The second half matters as much as the first: a name that genuinely resolves to nothing must
 * still answer 404 on the first attempt, with no retry and no delay. A retry that could not tell the
 * two apart would turn every mistyped submodule url into a 15-second wait.
 */
@QuarkusTest
@TestProfile(RepositoryNameCutoverTest.OneLostConnection.class)
public class RepositoryNameCutoverTest {

  /**
   * Enables the failing alias table for this class alone. A {@code QuarkusTestProfile} is the only
   * way to scope an {@code @Alternative}: one carrying {@code @Priority} is enabled for the whole
   * suite, and every repository read in it would go through the stand-in.
   */
  public static class OneLostConnection implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(ConnectionLosingRepositoryNames.class);
    }
  }

  @Inject ConnectionLosingRepositoryNames names;

  @BeforeEach
  public void healthy() {
    names.loseTheConnection(0);
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

  private String createRepository(String projectId, String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                null, name, RepositoryArchetype.SERVICE))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  private io.restassured.response.Response resolveByName(String projectId, String repoName) {
    return given()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories/by-name/" + repoName);
  }

  @Test
  public void aNameResolvesAfterTheReadLosesItsConnection() {
    String projectId = createProject("Cutover Resolution");
    String repoId = createRepository(projectId, "checkout");

    names.loseTheConnection(1);
    resolveByName(projectId, "checkout")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositoryId", equalTo(repoId));
    Assertions.assertEquals(
        0, names.unspent(), "the armed failure was never reached — the read did not go through");
  }

  /**
   * The retry does not invent a repository. A cutover on the way to a name nobody registered still
   * answers the honest 404 once the database is back.
   */
  @Test
  public void anAbsentNameStillAnswers404AfterACutover() {
    String projectId = createProject("Cutover Absence");

    names.loseTheConnection(1);
    resolveByName(projectId, "never-created")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    Assertions.assertEquals(0, names.unspent(), "the armed failure was never reached");
  }

  /** No cutover, no retry: an absent name is a first-attempt 404, exactly as it was. */
  @Test
  public void anAbsentNameIs404WithNoRetryAtAll() {
    String projectId = createProject("Plain Absence");

    long startedAt = System.nanoTime();
    resolveByName(projectId, "never-created")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    Assertions.assertTrue(
        elapsedMs < 5_000,
        "an absent name waited " + elapsedMs + "ms — a missing row is being retried as an outage");
  }
}
