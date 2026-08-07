package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ProjectControllerTest {

  @Inject ProjectService projectService;

  private final String fixtureUrl;

  public ProjectControllerTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  @Test
  public void testCreateAndGetAndListAndUpdateAndDelete() {
    // Create
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    "Ctrl Project", null, "Desc", null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("project.id", notNullValue())
            .body("project.name", equalTo("Ctrl Project"))
            .body("project.description", equalTo("Desc"))
            .extract()
            .path("project.id");

    // Get
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.id", equalTo(id))
        .body("project.name", equalTo("Ctrl Project"));

    // List
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.project.id", hasItem(id));

    // Update
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.UpdateProjectRequest("Updated Name", "Updated Desc"))
        .when()
        .put("/projects/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.name", equalTo("Updated Name"))
        .body("project.description", equalTo("Updated Desc"));

    // Delete
    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/projects/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    // Get after delete should 404
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects/" + id)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testCreateValidationErrors() {
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest("", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }

  @Test
  public void testUpdateNotFound() {
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.UpdateProjectRequest("Name", null))
        .when()
        .put("/projects/api/projects/non-existent")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testDeleteNotFound() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/projects/api/projects/non-existent")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testDeleteProjectWithAssociatedRepositories() {
    // Create project
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    "Delete Project", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    // Create repository under project
    String repoId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(
                    fixtureUrl, null, RepositoryArchetype.SERVICE))
            .when()
            .post("/projects/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    // Delete project (should cascade delete repositories)
    given()
        .contentType(ContentType.JSON)
        .when()
        .delete("/projects/api/projects/" + projectId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    // Project is gone
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects/" + projectId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    // Repository is also gone
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testShortcutCreateRepositoryUnderProject() {
    // Create project
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    "Shortcut Project", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    // Shortcut create repository under project
    String repoId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(
                    fixtureUrl, null, RepositoryArchetype.SERVICE))
            .when()
            .post("/projects/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repository.id", notNullValue())
            .body("projectId", equalTo(projectId))
            .extract()
            .path("repository.id");

    // Verify it's listed under project
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.repository.id", hasItem(repoId));
  }

  // SEAM (migration-plan.md §6, project <-> featureflow):
  // testFeatureFlowConfigurationCrudUnderProject is not carried over.
  // It exercised GET/POST /projects/{id}/feature-flow-configurations, the two sub-resources cut
  // from ProjectController — domain.featureflow is monolith-only and deferred (§9 item 6).
  @Test
  public void testCreateRejectsAnIllFormedSlug() {
    for (String bad : new String[] {"Upper", "-leading", "trailing-", "has space", "wrap.git"}) {
      given()
          .contentType(ContentType.JSON)
          .body(
              new ProjectController.CreateProjectRequest(
                  "Slug Check", bad, null, null, ProjectRequests.DNS))
          .when()
          .post("/projects/api/projects")
          .then()
          .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    }
  }

  /** Creation always ends with one repository, and the response hands it back. */
  @Test
  public void testCreateReturnsTheWrapperRepository() {
    given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Wrapper Resp", "wrapper-resp", null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.slug", equalTo("wrapper-resp"))
        .body("wrapper.archetype", equalTo("PROJECT"));
  }

  /** Adopting an upstream whose basename is not <slug>-<slug> breaks the alias invariant. */
  @Test
  public void testCreateRejectsAnAdoptUrlThatDoesNotMatchTheWrapperName() {
    given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Mismatch",
                "mismatch",
                null,
                "https://example.com/something-else.git",
                ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  /** The wrapper is created with the project, never through the repositories endpoint. */
  @Test
  public void testCreateRepositoryRejectsTheProjectArchetype() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    "No Second", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, RepositoryArchetype.PROJECT))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  // --- the project's domain (main-environment-plan.md §1) ---

  /**
   * {@code dns} is required, and this is the whole of what "breaking API change" means for a
   * client: the payload that worked before this feature is now a 400. Raw JSON rather than the
   * record, because the record cannot express an absent component.
   */
  @Test
  public void testCreateWithoutADnsObjectIsRejected() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"No Dns\"}")
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }

  /**
   * The domain becomes what an authoritative nameserver answers, so the charset is not a matter of
   * taste. {@code UPPER.CASE} is pinned as a rejection and not a normalisation: it is a second
   * spelling of one name, and silently rewriting a caller's input would make a later "did the
   * record change?" unanswerable.
   */
  @Test
  public void testCreateRejectsAMalformedOrHostileDomain() {
    String[] hostile = {
      "evil domain", // whitespace
      "UPPER.CASE", // lowercase only, deliberately not normalised
      "single", // one label is a zone apex a registrar delegates
      "-leading.eu",
      "trailing-.eu",
      "double..dot.eu",
      "under_score.eu",
      "a".repeat(250) + ".eu", // past the 253-character cap
      "", // blank
    };
    for (String bad : hostile) {
      given()
          .contentType(ContentType.JSON)
          .body(
              new ProjectController.CreateProjectRequest(
                  "Hostile Domain",
                  null,
                  null,
                  null,
                  new ProjectController.CreateProjectRequest.DnsSpec(
                      bad, ProjectDnsRecordType.A, "203.0.113.9")))
          .when()
          .post("/projects/api/projects")
          .then()
          .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    }
  }

  /**
   * A value is required for <b>every</b> type, CNAME included — a CNAME with no target is not a
   * record — and it may carry no whitespace or control characters.
   */
  @Test
  public void testCreateRejectsAMissingOrWhitespacedValue() {
    String[] bad = {null, "", "   ", "203.0.113.9 ", "two values", "line\nbreak", "tab\there"};
    for (String value : bad) {
      given()
          .contentType(ContentType.JSON)
          .body(
              new ProjectController.CreateProjectRequest(
                  "Bad Value",
                  null,
                  null,
                  null,
                  new ProjectController.CreateProjectRequest.DnsSpec(
                      "value.test.eu", ProjectDnsRecordType.CNAME, value)))
          .when()
          .post("/projects/api/projects")
          .then()
          .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    }
  }

  /** The type is required and is one of the three. */
  @Test
  public void testCreateRejectsAMissingType() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"No Type\",\"dns\":{\"domain\":\"a.test.eu\",\"value\":\"203.0.113.9\"}}")
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }

  /** The embeddable survives the round trip: what was posted is what a later GET reads back. */
  @Test
  public void testTheDnsRecordRoundTripsThroughCreateAndGet() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    "Round Trip",
                    "round-trip",
                    null,
                    null,
                    new ProjectController.CreateProjectRequest.DnsSpec(
                        "app.round-trip.test.eu", ProjectDnsRecordType.CNAME, "ingress.test.eu")))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("project.dns.domain", equalTo("app.round-trip.test.eu"))
            .body("project.dns.type", equalTo("CNAME"))
            .body("project.dns.value", equalTo("ingress.test.eu"))
            .extract()
            .path("project.id");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.dns.domain", equalTo("app.round-trip.test.eu"))
        .body("project.dns.type", equalTo("CNAME"))
        .body("project.dns.value", equalTo("ingress.test.eu"));
  }

  /**
   * A project with no record serves {@code "dns": null} — not an object of three nulls.
   *
   * <p>Created through the service, which is the only way to reach that state now that the API
   * demands the object: it is the state of every row predating the columns and of a self-seed with
   * no domain configured, so "no domain" has to stay ONE thing a client can test for. Hibernate
   * reads an {@code @Embedded} whose every column is null as a null field, and this is what pins
   * that it still does.
   */
  @Test
  public void testAProjectWithNoRecordServesANullDns() {
    var project = projectService.create("Legacy Row", "legacy-row", null);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/projects/" + project.id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("project.dns", nullValue());
  }
}
