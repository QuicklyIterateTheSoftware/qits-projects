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

    @Test
    public void testCloneAndPull() {
        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.CloneRepositoryRequest(fixtureUrl, null))
        .when()
            .post("/api/repositories/myrepo/clone")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", equalTo("myrepo"))
            .body("repository.url", equalTo(fixtureUrl));

        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.PullRepositoryRequest())
        .when()
            .post("/api/repositories/myrepo/pull")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());
    }

    @Test
    public void testCloneDuplicateId() {
        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.CloneRepositoryRequest(fixtureUrl, null))
        .when()
            .post("/api/repositories/duprepo/clone")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.CloneRepositoryRequest(fixtureUrl + "2", null))
        .when()
            .post("/api/repositories/duprepo/clone")
        .then()
            .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }
}
