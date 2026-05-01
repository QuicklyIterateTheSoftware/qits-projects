package eu.wohlben.qits.domain.project.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

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
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "ctrl-proj", "Ctrl Project", "Desc"
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("project.id", equalTo("ctrl-proj"))
            .body("project.name", equalTo("Ctrl Project"))
            .body("project.description", equalTo("Desc"));

        // Get
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/ctrl-proj")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("project.id", equalTo("ctrl-proj"))
            .body("project.name", equalTo("Ctrl Project"));

        // List
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("entries.project.id", hasItem("ctrl-proj"));

        // Update
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.UpdateProjectRequest(
                "Updated Name", "Updated Desc"
            ))
        .when()
            .put("/api/projects/ctrl-proj")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("project.name", equalTo("Updated Name"))
            .body("project.description", equalTo("Updated Desc"));

        // Delete
        given()
            .contentType(ContentType.JSON)
        .when()
            .delete("/api/projects/ctrl-proj")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("success", equalTo(true));

        // Get after delete should 404
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/ctrl-proj")
        .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testCreateDuplicateId() {
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "dup-proj-id", "First", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "dup-proj-id", "Second", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testCreateValidationErrors() {
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "", "", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(anyOf(
                equalTo(Response.Status.BAD_REQUEST.getStatusCode()),
                equalTo(422)
            ));
    }

    @Test
    public void testUpdateNotFound() {
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.UpdateProjectRequest(
                "Name", null
            ))
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
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "del-proj", "Delete Project", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        // Shortcut create repository under project
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(
                "del-repo", fixtureUrl, null
            ))
        .when()
            .post("/api/projects/del-proj/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        // Delete project (should succeed even with associated repos)
        given()
            .contentType(ContentType.JSON)
        .when()
            .delete("/api/projects/del-proj")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("success", equalTo(true));

        // Project is gone
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/del-proj")
        .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());

        // Repo should now be dangling — prove it by associating to a new project
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "del-proj-2", "New Project", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.AssociateRepositoryRequest("del-repo"))
        .when()
            .put("/api/projects/del-proj-2/associate")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", equalTo("del-repo"))
            .body("projectId", equalTo("del-proj-2"));
    }

    @Test
    public void testAssociateAndDisassociateRepository() {
        // Create project
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "assoc-proj", "Assoc Project", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        // Create repository separately (dangling)
        given()
            .contentType(ContentType.JSON)
            .body(new eu.wohlben.qits.domain.repository.api.RepositoryController.CloneRepositoryRequest(
                fixtureUrl, null
            ))
        .when()
            .post("/api/repositories/assoc-repo/clone")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        // List repos should be empty
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/assoc-proj/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("entries", empty());

        // Associate
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.AssociateRepositoryRequest("assoc-repo"))
        .when()
            .put("/api/projects/assoc-proj/associate")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", equalTo("assoc-repo"))
            .body("projectId", equalTo("assoc-proj"));

        // List repos should contain associated repo
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/assoc-proj/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("entries.repository.id", hasItem("assoc-repo"));

        // Disassociate
        given()
            .contentType(ContentType.JSON)
        .when()
            .delete("/api/projects/assoc-proj/associate/assoc-repo")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", equalTo("assoc-repo"));

        // List repos should be empty again
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/assoc-proj/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("entries", empty());
    }

    @Test
    public void testShortcutCreateRepositoryUnderProject() {
        // Create project
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "shortcut-proj", "Shortcut Project", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        // Shortcut create repository under project
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(
                "shortcut-repo", fixtureUrl, null
            ))
        .when()
            .post("/api/projects/shortcut-proj/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", equalTo("shortcut-repo"))
            .body("projectId", equalTo("shortcut-proj"));

        // Verify it's listed under project
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/projects/shortcut-proj/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("entries.repository.id", hasItem("shortcut-repo"));
    }

    @Test
    public void testAssociateNotFoundProject() {
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.AssociateRepositoryRequest("some-repo"))
        .when()
            .put("/api/projects/non-existent/associate")
        .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testAssociateNotFoundRepository() {
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "assoc-missing-repo-proj", "Proj", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.AssociateRepositoryRequest("non-existent-repo"))
        .when()
            .put("/api/projects/assoc-missing-repo-proj/associate")
        .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testDisassociateNotFound() {
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest(
                "disassoc-proj", "Disassoc", null
            ))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        given()
            .contentType(ContentType.JSON)
        .when()
            .delete("/api/projects/disassoc-proj/associate/non-existent")
        .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }
}
