package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * REST round-trips for the epics boundary hosted by {@code service}.
 *
 * <p>The monorepo ran this under the forwardauth auth variant, whose fallback identity is {@code
 * dev}, and asserted it through the audit "changed-by". This repo now resolves its principal from
 * qits-gateway's {@code X-Qits-User} header instead (migration-auth-plan.md), but the caller here
 * stays named with {@code @TestSecurity}: this test is about the epic/feature/task lifecycle, and
 * naming the caller directly keeps it independent of how the identity arrives.
 *
 * <p>Which means it cannot vouch for that arrival — {@code @TestSecurity} bypasses the
 * authentication mechanism entirely, and did so silently for the whole period this repo shipped no
 * mechanism at all. {@link EpicsAuditIdentityTest} is the test that exercises the real header path;
 * do not fold the two together.
 */
@QuarkusTest
class EpicApiTest {

  private final String fixtureUrl;

  EpicApiTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  private String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest("Epics Project", null, null, null))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null, false))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  @Test
  @TestSecurity(user = "dev")
  void fullEpicFeatureTaskLifecycle() {
    String projectId = createProject();
    String repoId = createRepository(projectId);

    // Create an epic under the project.
    String epicId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectEpicsController.CreateEpicRequest("Planning domain", "The spine"))
            .when()
            .post("/projects/api/projects/" + projectId + "/epics")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("epic.id", notNullValue())
            .body("epic.projectId", equalTo(projectId))
            .body("epic.title", equalTo("Planning domain"))
            .extract()
            .path("epic.id");

    // Get + list.
    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.id", equalTo(epicId));
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries.epic.id", hasItem(epicId));

    // Update.
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.UpdateEpicRequest("Planning domain v2", "Longer"))
        .when()
        .put("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.title", equalTo("Planning domain v2"));

    // Create a feature under the epic.
    String featureId =
        given()
            .contentType(ContentType.JSON)
            .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
            .when()
            .post("/projects/api/epics/" + epicId + "/features")
            .then()
            .statusCode(200)
            .body("feature.epicId", equalTo(epicId))
            .extract()
            .path("feature.id");

    // Create a task under the feature, bound to a real repository.
    String taskId =
        given()
            .contentType(ContentType.JSON)
            .body(new FeatureController.CreateTaskRequest(repoId, "Task 1", null, null))
            .when()
            .post("/projects/api/features/" + featureId + "/tasks")
            .then()
            .statusCode(200)
            .body("task.repositoryId", equalTo(repoId))
            .body("task.featureId", equalTo(featureId))
            .extract()
            .path("task.id");

    given().when().get("/projects/api/features/" + featureId).then().statusCode(200);
    given().when().get("/projects/api/tasks/" + taskId).then().statusCode(200);

    // Audit subtree: every create landed with the forwardauth `dev` identity.
    given()
        .when()
        .get("/projects/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("CREATE"))
        .body("entries.operation", hasItem("UPDATE"))
        .body("entries.changedBy", hasItem("dev"))
        .body("entries.entityId", hasItem(taskId));

    // Delete the epic cascades features + tasks.
    given()
        .when()
        .delete("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("success", equalTo(true));
    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .when()
        .get("/projects/api/features/" + featureId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .when()
        .get("/projects/api/tasks/" + taskId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    // The audit trail survives deletion (queried by epicId) and now carries the DELETE rows.
    given()
        .when()
        .get("/projects/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("DELETE"))
        .body("entries.entityId", hasItem(taskId));
  }

  @Test
  void taskCannotBindRepositoryFromAnotherProject() {
    String projectA = createProject();
    String projectB = createProject();
    String repoInB = createRepository(projectB);

    String epicId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectEpicsController.CreateEpicRequest("E", null))
            .when()
            .post("/projects/api/projects/" + projectA + "/epics")
            .then()
            .statusCode(200)
            .extract()
            .path("epic.id");
    String featureId =
        given()
            .contentType(ContentType.JSON)
            .body(new EpicController.CreateFeatureRequest("F", null, null))
            .when()
            .post("/projects/api/epics/" + epicId + "/features")
            .then()
            .statusCode(200)
            .extract()
            .path("feature.id");

    // repoInB exists but belongs to projectB, not the epic's projectA → rejected.
    given()
        .contentType(ContentType.JSON)
        .body(new FeatureController.CreateTaskRequest(repoInB, "T", null, null))
        .when()
        .post("/projects/api/features/" + featureId + "/tasks")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  void createEpicUnderUnknownProjectIs404() {
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest("X", null))
        .when()
        .post("/projects/api/projects/ghost/epics")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  void blankEpicTitleIsRejected() {
    String projectId = createProject();
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest("  ", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }

  @Test
  void createTaskWithUnknownRepositoryIs404() {
    String projectId = createProject();
    String epicId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectEpicsController.CreateEpicRequest("E", null))
            .when()
            .post("/projects/api/projects/" + projectId + "/epics")
            .then()
            .statusCode(200)
            .extract()
            .path("epic.id");
    String featureId =
        given()
            .contentType(ContentType.JSON)
            .body(new EpicController.CreateFeatureRequest("F", null, null))
            .when()
            .post("/projects/api/epics/" + epicId + "/features")
            .then()
            .statusCode(200)
            .extract()
            .path("feature.id");

    given()
        .contentType(ContentType.JSON)
        .body(new FeatureController.CreateTaskRequest("no-such-repo", "T", null, null))
        .when()
        .post("/projects/api/features/" + featureId + "/tasks")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  void unknownFeatureDependencyIsRejected() {
    String projectId = createProject();
    String epicId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectEpicsController.CreateEpicRequest("E", null))
            .when()
            .post("/projects/api/projects/" + projectId + "/epics")
            .then()
            .statusCode(200)
            .extract()
            .path("epic.id");

    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("F", null, "ghost-feature"))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  void auditListsCreateUpdateDeleteForAnEpic() {
    String projectId = createProject();
    String epicId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectEpicsController.CreateEpicRequest("E", null))
            .when()
            .post("/projects/api/projects/" + projectId + "/epics")
            .then()
            .statusCode(200)
            .extract()
            .path("epic.id");
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.UpdateEpicRequest("E2", null))
        .when()
        .put("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200);

    // Newest first: UPDATE then CREATE (delete would remove the epic and 404 the audit endpoint).
    given()
        .when()
        .get("/projects/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", contains("UPDATE", "CREATE"));
  }
}
