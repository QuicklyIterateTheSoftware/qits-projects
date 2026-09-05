package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The refinement lifecycle, REST-level and end to end against the fakes: find-or-create keyed by
 * epic (idempotent, branch cut on the wrapper), the async ensure ladder, the recreate gate, the
 * prompt draft and attachments, and the discard teardown order.
 */
@QuarkusTest
public class RefinementLifecycleTest {

  @Inject FakeRefinementRuntime runtime;
  @Inject FakeRefinementCredentials credentials;
  @Inject RefinementCommissions commissions;
  @Inject RefinementService service;
  @Inject eu.wohlben.qits.projects.control.TechnicalProcessRegistry processes;

  @BeforeEach
  void reset() {
    runtime.reset();
    credentials.reset();
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
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
        .body(java.util.Map.of("title", title, "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private io.restassured.response.Response open(String epicId) {
    return given()
        .contentType(ContentType.JSON)
        .body(java.util.Map.of("epicId", epicId))
        .when()
        .post("/projects/api/refinements");
  }

  private void awaitStatus(long id, String expected) {
    Instant giveUp = Instant.now().plus(Duration.ofSeconds(10));
    String last = null;
    while (Instant.now().isBefore(giveUp)) {
      last =
          given()
              .when()
              .get("/projects/api/refinements/" + id)
              .then()
              .statusCode(200)
              .extract()
              .path("refinement.runtimeStatus");
      if (expected.equals(last)) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("refinement " + id + " never reached " + expected + "; last " + last);
  }

  @Test
  public void openIsIdempotentPerEpicAndCutsTheRefiningBranch() {
    String projectId = createProject("Refine Open");
    String epicId = createEpic(projectId, "Sharper Onboarding");

    Number first =
        open(epicId)
            .then()
            .statusCode(200)
            .body("refinement.epicId", equalTo(epicId))
            .body("refinement.projectId", equalTo(projectId))
            .body("refinement.branch", equalTo("refining/sharper-onboarding"))
            .body("refinement.parent", equalTo("main"))
            .body("refinement.preamble", notNullValue())
            .extract()
            .path("refinement.id");

    Number second = open(epicId).then().statusCode(200).extract().path("refinement.id");
    assertEquals(first.longValue(), second.longValue());
  }

  @Test
  public void openRefusesAnEpicThatIsNotRefining() {
    String projectId = createProject("Refine Frozen");
    String epicId = createEpic(projectId, "Frozen Epic");
    given()
        .contentType(ContentType.JSON)
        .body(java.util.Map.of("target", "IMPLEMENTATION"))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then()
        .statusCode(200);

    open(epicId).then().statusCode(409);
  }

  @Test
  public void ensureProvisionsAFreshContainerOffTheRequestThread() {
    String projectId = createProject("Refine Ensure");
    String epicId = createEpic(projectId, "Ensure Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");

    String processId =
        given()
            .when()
            .post("/projects/api/refinements/" + id + "/ensure-container")
            .then()
            .statusCode(200)
            .body("technicalProcessId", notNullValue())
            .extract()
            .path("technicalProcessId");

    awaitStatus(id.longValue(), "RUNNING");
    assertTrue(runtime.calls().contains("provision:" + id.longValue()));

    // The narration is subscribable while the daemon would still be dialling home. Asserted at the
    // registry rather than by GETting the SSE route: the stream stays open until the process
    // settles, and a blocking client would sit on it for the whole idle window.
    assertTrue(processes.find(processId).isPresent());
  }

  @Test
  public void ensureOfARunningContainerIsATouchAndSettlesItsNarration() {
    String projectId = createProject("Refine Touch");
    String epicId = createEpic(projectId, "Touch Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");
    runtime.place(id.longValue(), "qits-ref-x", true);

    given()
        .when()
        .post("/projects/api/refinements/" + id + "/ensure-container")
        .then()
        .statusCode(200);
    awaitStatus(id.longValue(), "RUNNING");
    Instant giveUp = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(giveUp) && !runtime.calls().contains("touch:" + id.longValue())) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertTrue(runtime.calls().contains("touch:" + id.longValue()));
    assertFalse(runtime.calls().contains("provision:" + id.longValue()));

    // The active process settles on the spot for a running container.
    given()
        .when()
        .get("/projects/api/refinements/" + id + "/active-process")
        .then()
        .statusCode(200)
        .body("technicalProcessId", nullValue());
  }

  @Test
  public void recreateIsGatedOnADaemonVouchedCleanTree() {
    String projectId = createProject("Refine Recreate");
    String epicId = createEpic(projectId, "Recreate Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");
    runtime.place(id.longValue(), "qits-ref-x", true);

    // No daemon has vouched, so clean is unknown and the recreate is refused.
    given()
        .when()
        .post("/projects/api/refinements/" + id + "/recreate-container")
        .then()
        .statusCode(400);
  }

  @Test
  public void stopLeavesThePlaceAndItsVolume() {
    String projectId = createProject("Refine Stop");
    String epicId = createEpic(projectId, "Stop Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");
    runtime.place(id.longValue(), "qits-ref-x", true);

    given()
        .when()
        .post("/projects/api/refinements/" + id + "/stop-container")
        .then()
        .statusCode(200);
    assertTrue(runtime.calls().contains("stop:" + id.longValue()));
    assertFalse(runtime.calls().contains("delete:" + id.longValue()));
  }

  @Test
  public void discardRemovesContainerVolumeCredentialAndRow() {
    credentials.enable(true);
    String projectId = createProject("Refine Discard");
    String epicId = createEpic(projectId, "Discard Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");

    given()
        .when()
        .post("/projects/api/refinements/" + id + "/ensure-container")
        .then()
        .statusCode(200);
    awaitStatus(id.longValue(), "RUNNING");
    // The fake runtime bypasses the factory, so commission the row the way the fresh arm would —
    // what discard must then hand back.
    commissions.forFreshContainer(service.get(id.longValue()));
    assertEquals(1, credentials.liveCount());
    // The commission names WHICH context (the refinement) and what it is ABOUT (the project). The
    // second is the scope qits-idp turns into a `project` claim, and it is not the refinement id.
    assertEquals(
        projectId,
        credentials.scopeFor(id.longValue()),
        "a refinement's credential is scoped to its project");

    given()
        .when()
        .post("/projects/api/refinements/" + id + "/discard")
        .then()
        .statusCode(200)
        .body("success", equalTo(true));
    assertTrue(runtime.calls().contains("delete:" + id.longValue()));
    assertEquals(0, credentials.liveCount());

    given().when().get("/projects/api/refinements/" + id).then().statusCode(404);

    // The next open starts afresh rather than finding a ghost.
    Number again = open(epicId).then().statusCode(200).extract().path("refinement.id");
    assertNotEquals(id.longValue(), again.longValue());
  }

  @Test
  public void promptDraftRoundTripsAndIsGoneWithADelete() {
    String projectId = createProject("Refine Draft");
    String epicId = createEpic(projectId, "Draft Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");

    given().when().get("/projects/api/refinements/" + id + "/prompt-draft").then().statusCode(404);

    given()
        .contentType(ContentType.JSON)
        .body(java.util.Map.of("content", "{\"blocks\":[]}", "serializedPrompt", "hello"))
        .when()
        .put("/projects/api/refinements/" + id + "/prompt-draft")
        .then()
        .statusCode(200)
        .body("draft.content", equalTo("{\"blocks\":[]}"))
        .body("draft.updatedAt", notNullValue());

    given()
        .contentType(ContentType.JSON)
        .body(java.util.Map.of("content", "not json"))
        .when()
        .put("/projects/api/refinements/" + id + "/prompt-draft")
        .then()
        .statusCode(400);

    given()
        .when()
        .delete("/projects/api/refinements/" + id + "/prompt-draft")
        .then()
        .statusCode(204);
    given().when().get("/projects/api/refinements/" + id + "/prompt-draft").then().statusCode(404);
  }

  @Test
  public void attachmentsSniffBytesAndServeBrowserLoadableContent() {
    String projectId = createProject("Refine Attach");
    String epicId = createEpic(projectId, "Attach Epic");
    Number id = open(epicId).then().statusCode(200).extract().path("refinement.id");

    byte[] png =
        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    String attachmentId =
        given()
            .contentType(ContentType.JSON)
            .body(
                java.util.Map.of(
                    "mimeType", "image/gif", // the claim is ignored; the bytes say PNG
                    "label", "sketch",
                    "source", "SKETCH",
                    "dataBase64", Base64.getEncoder().encodeToString(png)))
            .when()
            .post("/projects/api/refinements/" + id + "/prompt-attachments")
            .then()
            .statusCode(201)
            .body("mimeType", equalTo("image/png"))
            .extract()
            .path("id");

    given()
        .when()
        .get("/projects/api/refinements/" + id + "/prompt-attachments")
        .then()
        .statusCode(200)
        .body("attachments[0].id", equalTo(attachmentId))
        .body("attachments[0].dataBase64", notNullValue());

    given()
        .when()
        .get(
            "/projects/api/refinements/"
                + id
                + "/prompt-attachments/"
                + attachmentId
                + "/content")
        .then()
        .statusCode(200)
        .contentType("image/png");

    given()
        .contentType(ContentType.JSON)
        .body(
            java.util.Map.of(
                "label", "junk", "source", "PASTE", "dataBase64",
                Base64.getEncoder().encodeToString("not an image".getBytes())))
        .when()
        .post("/projects/api/refinements/" + id + "/prompt-attachments")
        .then()
        .statusCode(400);

    given()
        .when()
        .delete("/projects/api/refinements/" + id + "/prompt-attachments/" + attachmentId)
        .then()
        .statusCode(204);
  }
}
