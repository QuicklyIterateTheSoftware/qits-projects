package eu.wohlben.qits.domain.repository.api;

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
@TestProfile(RepositoryControllerTest.TestProfile.class)
public class RepositoryControllerTest {

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

    public RepositoryControllerTest() throws Exception {
        fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    }

    private String createProjectAndRepository() {
        String projectId = given()
            .contentType(ContentType.JSON)
            .body(new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest("Repo Project", null))
        .when()
            .post("/api/projects")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract().path("project.id");

        return given()
            .contentType(ContentType.JSON)
            .body(new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null))
        .when()
            .post("/api/projects/" + projectId + "/repositories")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract().path("repository.id");
    }

    @Test
    public void testGetAndDelete() {
        String repoId = createProjectAndRepository();

        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/repositories/" + repoId)
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", equalTo(repoId));

        given()
            .contentType(ContentType.JSON)
        .when()
            .delete("/api/repositories/" + repoId)
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("success", equalTo(true));
    }

    @Test
    public void testPullAndPush() {
        String repoId = createProjectAndRepository();

        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.PullRepositoryRequest())
        .when()
            .post("/api/repositories/" + repoId + "/pull")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.PushRepositoryRequest())
        .when()
            .post("/api/repositories/" + repoId + "/push")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());
    }
}
