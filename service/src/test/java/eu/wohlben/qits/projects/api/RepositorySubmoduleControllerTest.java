package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * REST surface for a superproject's submodules: imported edges + available (unimported) entries and
 * the layer-by-layer import action.
 */
@QuarkusTest
public class RepositorySubmoduleControllerTest {

  private String importRepository(String fixture) throws Exception {
    return importRepository(fixture, null);
  }

  private String importRepository(String fixture, Boolean importSubmodules) throws Exception {
    String url = GitFixtures.path(fixture);
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRequest(
                    "Submodule API", null, null, null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");
    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRepositoryRequest(
                url, null, importSubmodules))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  @Test
  public void listsTheSuperprojectSubmoduleEdges() throws Exception {
    // importSubmodules omitted -> defaults to true: the two DIRECT edges exist, nothing is left
    // available at this level.
    String superId = importRepository("submodule-super.git");
    given()
        .when()
        .get("/api/repositories/" + superId + "/submodules")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(2))
        .body("entries.submodule.path", containsInAnyOrder("child-a", "shared-direct"))
        .body("entries.submodule.parentRepoId", containsInAnyOrder(superId, superId))
        .body("available", empty());
  }

  @Test
  public void optOutLeavesSubmodulesAvailableAndImportActionImportsThem() throws Exception {
    String superId = importRepository("submodule-super.git", false);

    // Toggle off: nothing imported, both direct submodules advertised as available.
    given()
        .when()
        .get("/api/repositories/" + superId + "/submodules")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", empty())
        .body("available", hasSize(2))
        .body("available.path", containsInAnyOrder("child-a", "shared-direct"));

    // The detail view's action: import this level.
    given()
        .when()
        .post("/api/repositories/" + superId + "/submodules/import")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(2))
        .body("entries.submodule.path", containsInAnyOrder("child-a", "shared-direct"));

    // Idempotent: importing again changes nothing.
    given()
        .when()
        .post("/api/repositories/" + superId + "/submodules/import")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(2));
    given()
        .when()
        .get("/api/repositories/" + superId + "/submodules")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(2))
        .body("available", empty());
  }

  @Test
  public void prepareServesTheBackendAndImportDedupsOntoIt() throws Exception {
    // A superproject with an unimported `lib` -> ../submodule-shared.git submodule.
    String superId = importRepository("submodule-simple-super.git", false);
    String backend = GitFixtures.path("submodule-shared.git");

    // Pre-serve the backend as a sibling; the response tells the user what to `git submodule add`.
    given()
        .contentType(ContentType.JSON)
        .body(new RepositorySubmoduleController.PrepareSubmoduleBackendRequest(backend))
        .when()
        .post("/api/repositories/" + superId + "/submodules/prepare")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("name", is("submodule-shared"))
        .body("relativeUrl", is("../submodule-shared.git"));

    // The .gitmodules reference is still unimported (prepare only serves the backend), but
    // importing
    // now dedups onto the pre-served sibling rather than creating a duplicate.
    given()
        .when()
        .post("/api/repositories/" + superId + "/submodules/import")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(1))
        .body("entries.submodule.path", containsInAnyOrder("lib"));
  }

  @Test
  public void prepareRejectsAQitsHostBackend() throws Exception {
    String superId = importRepository("submodule-simple-super.git", false);
    given()
        .contentType(ContentType.JSON)
        .body(
            new RepositorySubmoduleController.PrepareSubmoduleBackendRequest(
                "http://qits:8080/git/proj/qits-gateway"))
        .when()
        .post("/api/repositories/" + superId + "/submodules/prepare")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void submoduleFreeRepositoryHasNoEdges() throws Exception {
    String repoId = importRepository("testing-repo.git");
    given()
        .when()
        .get("/api/repositories/" + repoId + "/submodules")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", empty())
        .body("available", empty());
  }
}
