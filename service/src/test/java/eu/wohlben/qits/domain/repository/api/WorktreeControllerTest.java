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
@TestProfile(WorktreeControllerTest.TestProfile.class)
public class WorktreeControllerTest {

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

    public WorktreeControllerTest() throws Exception {
        fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    }

    @Test
    public void testCreateWorktreeAndMergeAndDiscard() {
        // clone via RepositoryController
        given()
            .contentType(ContentType.JSON)
            .body(new RepositoryController.CloneRepositoryRequest(fixtureUrl, null))
        .when()
            .post("/repositories/wtrepo/clone")
        .then()
            .statusCode(Response.Status.OK.getStatusCode());

        // create worktree from feature branch
        given()
            .contentType(ContentType.JSON)
            .body(new WorktreeController.CreateWorktreeRequest("step-01", null, "feature"))
        .when()
            .post("/repositories/wtrepo/worktrees")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("id", equalTo("step-01"));

        // merge feature into master
        given()
            .contentType(ContentType.JSON)
            .body(new WorktreeController.MergeWorktreeRequest("master"))
        .when()
            .post("/repositories/wtrepo/worktrees/step-01/merge")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("hasConflicts", equalTo(false));

        // discard
        given()
            .contentType(ContentType.JSON)
            .body(new WorktreeController.DiscardWorktreeRequest())
        .when()
            .post("/repositories/wtrepo/worktrees/step-01/discard")
        .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("success", equalTo(true));
    }
}
