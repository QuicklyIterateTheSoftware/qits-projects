package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The audit "changed-by" against a real request header, end to end through the epics boundary.
 *
 * <p>This is the regression test the extraction left missing, and it covers a bug rather than a
 * hypothetical. {@code EpicsPrincipal.changedBy()} reads an injected {@link
 * io.quarkus.security.identity.SecurityIdentity}, but until qits-gateway's header contract landed
 * this repo shipped no authentication mechanism at all — so the identity was anonymous on every real
 * request and {@code changed_by} had been silently unwritten since extraction. The suite did not
 * notice because {@code EpicApiTest} names its caller with {@code @TestSecurity}, which bypasses the
 * mechanism entirely and so could never have caught it.
 *
 * <p>Hence: no {@code @TestSecurity} here. A real {@code X-Qits-User} header, resolved by the real
 * mechanism, is the only thing that proves the production path works. The dev-user fallback is
 * blanked so the no-header case is the deployed posture rather than the synthetic {@code dev}
 * identity.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class EpicsAuditIdentityTest {

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null))
        .when()
        .post("/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String header) {
    var request =
        given().contentType(ContentType.JSON).body(new ProjectEpicsController.CreateEpicRequest(
            "Audited epic", "Who changed it"));
    if (header != null) {
      request = request.header("X-Qits-User", header);
    }
    return request
        .when()
        .post("/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  @Test
  void theGatewayInjectedIdentityIsWrittenToTheAuditRow() {
    String epicId = createEpic(createProject("Audit identity"), "alice");

    given()
        .when()
        .get("/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.changedBy", hasItem("alice"));
  }

  @Test
  void withoutTheHeaderTheAuditRowIsUnattributed() {
    // Anonymous means "no name for the audit row" — the change still happens, it is just unnamed.
    // Explicitly NOT a denial: this service has no policy and must never grow one (§12).
    String epicId = createEpic(createProject("Audit anonymous"), null);

    given()
        .when()
        .get("/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.changedBy", everyItem(nullValue()));
  }
}
