package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.ws.rs.core.Response;
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
 *
 * <p><b>The roles header is part of that contract now.</b> Every REST boundary here carries {@code
 * @RolesAllowed("qits:admin")}, so the edge's {@code X-Qits-Roles} is what makes the identity able
 * to write at all — a request naming a user and no role authenticates and is then refused. That is
 * why {@link #session} sends both headers, and why the second test below asserts a refusal where it
 * once asserted an unattributed row: an anonymous write does not reach the audit log any more.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class EpicsAuditIdentityTest {

  /** A browser session as the edge forwards it: the user's name, and the role it signed in with. */
  private RequestSpecification session(String user) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", "qits:admin");
  }

  private String createProject(String name) {
    return session("operator")
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  @Test
  void theGatewayInjectedIdentityIsWrittenToTheAuditRow() {
    // The project is created by somebody else, so the name on the epic's audit row can only have
    // come from the header the epic call itself carried.
    String projectId = createProject("Audit identity");

    String epicId =
        session("alice")
            .body(new ProjectEpicsController.CreateEpicRequest("Audited epic", "Who changed it"))
            .when()
            .post("/projects/api/projects/" + projectId + "/epics")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("epic.id");

    session("alice")
        .when()
        .get("/projects/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.changedBy", hasItem("alice"));
  }

  @Test
  void anAnonymousWriteIsRefusedRatherThanRecordedUnattributed() {
    // This assertion is the inverse of the one it replaces. An unnamed caller used to write an
    // epic with a null changed_by, on the reasoning that anonymity is "no name for the audit row"
    // rather than a security state; the boundary is guarded now, so the row is never written and
    // there is nothing left to leave unattributed. 401 rather than 403: with no header there is no
    // identity at all, which is the challenge door rather than the roles door.
    String projectId = createProject("Audit anonymous");

    given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest("Unnamed epic", "Nobody"))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
  }

  @Test
  void aNamedCallerWithoutTheRoleIsRefusedToo() {
    // The other door, and the one that says the roles header is load-bearing rather than
    // decorative: this request has an identity — the audit row could have named it — and no grant.
    String projectId = createProject("Audit unauthorized");

    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "alice")
        .body(new ProjectEpicsController.CreateEpicRequest("Ungranted epic", "No role"))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }
}
