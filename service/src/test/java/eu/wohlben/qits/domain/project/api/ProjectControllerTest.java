package eu.wohlben.qits.domain.project.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProjectControllerTest.TestProfile.class)
public class ProjectControllerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private final String fixtureUrl;

  public ProjectControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  @Test
  public void testCreateAndGetAndListAndUpdateAndDelete() {
    // Create
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest("Ctrl Project", "Desc"))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("project.id", notNullValue())
            .body("project.name", equalTo("Ctrl Project"))
            .body("project.description", equalTo("Desc"))
            .extract()
            .path("project.id");

    // Get
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.id", equalTo(id))
        .body("project.name", equalTo("Ctrl Project"));

    // List
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.project.id", hasItem(id));

    // Update
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.UpdateProjectRequest("Updated Name", "Updated Desc"))
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.name", equalTo("Updated Name"))
        .body("project.description", equalTo("Updated Desc"));

    // Delete
    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    // Get after delete should 404
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects/" + id)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testCreateValidationErrors() {
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest("", null))
        .when()
        .post("/api/projects")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }

  @Test
  public void testUpdateNotFound() {
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.UpdateProjectRequest("Name", null))
        .when()
        .put("/api/projects/non-existent")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testDeleteNotFound() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/api/projects/non-existent")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testDeleteProjectWithAssociatedRepositories() {
    // Create project
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest("Delete Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    // Create repository under project
    String repoId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null))
            .when()
            .post("/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    // Delete project (should cascade delete repositories)
    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/api/projects/" + projectId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    // Project is gone
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects/" + projectId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    // Repository is also gone
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testShortcutCreateRepositoryUnderProject() {
    // Create project
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest("Shortcut Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    // Shortcut create repository under project
    String repoId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null))
            .when()
            .post("/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", notNullValue())
            .body("projectId", equalTo(projectId))
            .extract()
            .path("repository.id");

    // Verify it's listed under project
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.repository.id", hasItem(repoId));
  }

  @Test
  public void testFeatureFlowConfigurationCrudUnderProject() {
    // Create project
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest("Flow Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    // List should be empty
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects/" + projectId + "/feature-flow-configurations")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", empty());

    // Create feature flow configuration
    String configId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectFeatureFlowConfigurationRequest("My Flow"))
            .when()
            .post("/api/projects/" + projectId + "/feature-flow-configurations")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("featureFlowConfiguration.name", equalTo("My Flow"))
            .body("featureFlowConfiguration.projectId", equalTo(projectId))
            .extract()
            .path("featureFlowConfiguration.id");

    // List should contain the config
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/projects/" + projectId + "/feature-flow-configurations")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.featureFlowConfiguration.id", hasItem(configId));

    // Get via global endpoint
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/feature-flow-configurations/" + configId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("featureFlowConfiguration.name", equalTo("My Flow"));
  }
}
