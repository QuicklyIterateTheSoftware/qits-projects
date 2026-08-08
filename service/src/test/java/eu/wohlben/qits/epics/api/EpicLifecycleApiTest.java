package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle over HTTP: the transition endpoint's two-field response, the 409s it answers, and
 * the epics list's status filter. The rules themselves are pinned in the epics module's {@code
 * EpicLifecycleTest} — what is tested here is the wire shape the SPA is built against.
 */
@QuarkusTest
class EpicLifecycleApiTest {

  private String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Lifecycle Project", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest(title, "The spine"))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("REFINING"))
        .body("epic.supersededByEpicId", nullValue())
        .extract()
        .path("epic.id");
  }

  private ValidatableResponse transition(String epicId, String target) {
    return given()
        .contentType(ContentType.JSON)
        .body(new EpicController.TransitionEpicRequest(target))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then();
  }

  @Test
  void freezingAnEpicReturnsItWithoutASuccessor() {
    String epicId = createEpic(createProject(), "Planning domain");

    transition(epicId, "IMPLEMENTATION")
        .statusCode(200)
        .body("epic.id", equalTo(epicId))
        .body("epic.status", equalTo("IMPLEMENTATION"))
        .body("successor", nullValue());

    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("IMPLEMENTATION"));
  }

  @Test
  void supersedingReturnsTheSuccessorDraftAndLinksTheOldEpicToIt() {
    String projectId = createProject();
    String epicId = createEpic(projectId, "Planning domain");
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(200);
    transition(epicId, "IMPLEMENTATION").statusCode(200);

    String successorId =
        transition(epicId, "SUPERSEDED")
            .statusCode(200)
            .body("epic.status", equalTo("SUPERSEDED"))
            .body("epic.supersededByEpicId", notNullValue())
            .body("successor.id", not(equalTo(epicId)))
            .body("successor.status", equalTo("REFINING"))
            .body("successor.projectId", equalTo(projectId))
            .body("successor.title", equalTo("Planning domain"))
            .body("successor.supersededByEpicId", nullValue())
            .extract()
            .path("successor.id");

    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.supersededByEpicId", equalTo(successorId));

    // The copied scope came with it, slugs kept.
    given()
        .when()
        .get("/projects/api/epics/" + successorId + "/features")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].feature.slug", equalTo("feature-a"));
  }

  @Test
  void anIllegalOrUnknownTargetIs409() {
    String epicId = createEpic(createProject(), "Planning domain");

    // A draft has no scope to supersede.
    transition(epicId, "SUPERSEDED")
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", notNullValue());
    // "Done" is derived, not stored, so it names no status at all.
    transition(epicId, "DONE").statusCode(Response.Status.CONFLICT.getStatusCode());
    // Freezing is one-way.
    transition(epicId, "IMPLEMENTATION").statusCode(200);
    transition(epicId, "REFINING").statusCode(Response.Status.CONFLICT.getStatusCode());
  }

  @Test
  void aFrozenEpicRefusesStructuralEditsAndTakesItsMarkers() {
    String projectId = createProject();
    String epicId = createEpic(projectId, "Planning domain");
    String featureId =
        given()
            .contentType(ContentType.JSON)
            .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
            .when()
            .post("/projects/api/epics/" + epicId + "/features")
            .then()
            .statusCode(200)
            .extract()
            .path("feature.id");

    // While a draft, the marker is the thing that is refused.
    given()
        .contentType(ContentType.JSON)
        .body(
            new FeatureController.UpdateFeatureRequest(
                null, null, null, false, Instant.parse("2026-07-25T10:15:30Z"), false))
        .when()
        .put("/projects/api/features/" + featureId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());

    transition(epicId, "IMPLEMENTATION").statusCode(200);

    // Frozen: the title edit is refused and the marker goes through.
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.UpdateEpicRequest("Renamed", null))
        .when()
        .put("/projects/api/epics/" + epicId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("Feature B", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body(
            new FeatureController.UpdateFeatureRequest(
                null, null, null, false, Instant.parse("2026-07-25T10:15:30Z"), false))
        .when()
        .put("/projects/api/features/" + featureId)
        .then()
        .statusCode(200)
        .body("feature.implementedOn", notNullValue());
  }

  @Test
  void theEpicsListFiltersByStatus() {
    String projectId = createProject();
    String draftId = createEpic(projectId, "Still drafting");
    String frozenId = createEpic(projectId, "Being built");
    transition(frozenId, "IMPLEMENTATION").statusCode(200);

    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries", hasSize(2));
    given()
        .queryParam("status", "REFINING")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries.epic.id", hasItem(draftId));
    given()
        .queryParam("status", "IMPLEMENTATION")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries.epic.id", hasItem(frozenId));
    given()
        .queryParam("status", "ABANDONED")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries", hasSize(0));
    // A typo must not read as "no epics".
    given()
        .queryParam("status", "done")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  void aTransitionIsAuditedOnTheEpic() {
    String epicId = createEpic(createProject(), "Planning domain");
    transition(epicId, "ABANDONED").statusCode(200);

    given()
        .when()
        .get("/projects/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("UPDATE"));
  }
}
