package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectReconciliation.DomainAssertion;
import eu.wohlben.qits.projects.control.ProjectReconciliation.EnvironmentAssertion;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.testsupport.RecordingProjectDomainRegistrar;
import eu.wohlben.qits.projects.testsupport.RecordingProjectEnvironmentNotifier;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/projects/{projectId}/reconcile} at the HTTP boundary
 * (main-environment-plan.md §5): that both ports are driven through their <b>synchronous</b>
 * halves, and that whatever they answer reaches the caller unflattened — including a failure, which
 * is a 200 because the outcomes are the result.
 *
 * <p>Scripted port fakes rather than servers: what a status code becomes is {@code
 * CdEnvironmentNotifierTest} / {@code DnsDomainRegistrarTest}'s business against a real socket.
 * Here the question is only whether an outcome survives the trip out.
 */
@QuarkusTest
public class ProjectReconcileControllerTest {

  @Inject ProjectService projectService;
  @Inject RecordingProjectEnvironmentNotifier environments;
  @Inject RecordingProjectDomainRegistrar domains;

  /** One application, and therefore one of each scripted bean, is shared across the class. */
  @BeforeEach
  void clearRecordings() {
    environments.clear();
    domains.clear();
  }

  /** A project with a stored record, created through the API like any client would. */
  private String createProject(String slug, String domain) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                slug,
                slug,
                null,
                null,
                new ProjectController.CreateProjectRequest.DnsSpec(
                    domain, ProjectDnsRecordType.A, "203.0.113.9")))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  /** The missing project is the one error: nothing about it can be re-asserted. */
  @Test
  public void testReconcilingAnUnknownProjectIs404() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/no-such-project/reconcile")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /**
   * The happy path, and the assertion that matters most: the reconcile drives the ports'
   * <b>synchronous</b> halves with the project's own values — a reconcile that fired the
   * fire-and-forget methods would answer before anything had happened.
   */
  @Test
  public void testAReconcileReassertsBothTargetsSynchronouslyAndReportsThem() {
    String projectId = createProject("reconcile-ok", "reconcile-ok.test.eu");
    environments.willAnswer(EnvironmentAssertion.created());
    domains.willAnswer(DomainAssertion.registered());

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("environment", equalTo("CREATED"))
        .body("environmentDetail", nullValue())
        .body("domain", equalTo("REGISTERED"))
        .body("domainDetail", nullValue());

    var reassertion = environments.reassertions().stream().findFirst().orElseThrow();
    assertEquals(projectId, reassertion.projectId());
    assertEquals("reconcile-ok", reassertion.slug());

    var registration = domains.reassertions().stream().findFirst().orElseThrow();
    assertEquals("reconcile-ok.test.eu", registration.domain());
    assertEquals(ProjectDnsRecordType.A, registration.type());
    assertEquals("203.0.113.9", registration.value());
  }

  /**
   * The steady state a repeated reconcile finds: both receivers are idempotent, which is what makes
   * re-asserting legitimate rather than reckless.
   */
  @Test
  public void testTheAlreadyExistsAndNoMatchingZoneOutcomesSurface() {
    String projectId = createProject("reconcile-steady", "reconcile-steady.test.eu");
    environments.willAnswer(EnvironmentAssertion.alreadyExists());
    domains.willAnswer(DomainAssertion.noMatchingZone("No qits-dns zone contains this name."));

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("environment", equalTo("ALREADY_EXISTS"))
        .body("environmentDetail", nullValue())
        .body("domain", equalTo("NO_MATCHING_ZONE"))
        .body("domainDetail", containsString("No qits-dns zone"));
  }

  /**
   * <b>A failed target is still a 200.</b> The outcomes are what the caller asked for, and a 5xx
   * would throw away the half that worked — asserted here with both halves failing and their
   * reasons intact.
   */
  @Test
  public void testAFailedTargetIsStillA200WithItsReason() {
    String projectId = createProject("reconcile-down", "reconcile-down.test.eu");
    environments.willAnswer(EnvironmentAssertion.failed("qits-cd answered 500."));
    domains.willAnswer(DomainAssertion.failed("qits-dns is unreachable."));

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("environment", equalTo("FAILED"))
        .body("environmentDetail", containsString("qits-cd answered 500"))
        .body("domain", equalTo("FAILED"))
        .body("domainDetail", containsString("unreachable"));
  }

  /** And the mixed case, which is the whole reason a partial failure is not an error. */
  @Test
  public void testOneTargetFailingLeavesTheOthersOutcomeIntact() {
    String projectId = createProject("reconcile-half", "reconcile-half.test.eu");
    environments.willAnswer(EnvironmentAssertion.failed("qits-cd is unreachable."));
    domains.willAnswer(DomainAssertion.registered());

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("environment", equalTo("FAILED"))
        .body("domain", equalTo("REGISTERED"));
  }

  /**
   * A project with no stored record reports {@code NOT_CONFIGURED} and asks the registrar nothing —
   * the documented state of a row predating the columns and of a self-seed with no dns
   * configuration, not a failure. Created through the service, which is the only way to reach that
   * state now that the API demands the object.
   */
  @Test
  public void testAProjectWithNoRecordReportsNotConfigured() {
    var project = projectService.create("Reconcile No Dns", "reconcile-no-dns", null);
    domains.clear();

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + project.id + "/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("environment", equalTo("CREATED"))
        .body("domain", equalTo("NOT_CONFIGURED"))
        .body("domainDetail", containsString("no dns record"));

    assertTrue(
        domains.reassertions().isEmpty(), "there is no domain to re-assert, so nothing was asked");
  }
}
