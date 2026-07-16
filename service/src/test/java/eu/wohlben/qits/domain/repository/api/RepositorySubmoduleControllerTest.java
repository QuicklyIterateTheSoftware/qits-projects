package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REST surface for a superproject's submodules: imported edges + available (unimported) entries and
 * the layer-by-layer import action.
 */
@QuarkusTest
@TestProfile(RepositorySubmoduleControllerTest.TestProfile.class)
public class RepositorySubmoduleControllerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-submodule-api");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private String importRepository(String fixture) throws Exception {
    return importRepository(fixture, null);
  }

  private String importRepository(String fixture, Boolean importSubmodules) throws Exception {
    String url = getClass().getResource("/fixtures/" + fixture).toURI().getPath();
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Submodule API", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");
    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRepositoryRequest(
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
