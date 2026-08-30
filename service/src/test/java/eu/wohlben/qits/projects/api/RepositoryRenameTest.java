package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.RecordingRepositoryAnnouncer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * {@code PATCH /projects/api/repositories/{repoId}} — the rename, which is the whole of what
 * changing a repository's public identity is on this platform.
 *
 * <p>What these cases are really about, in one sentence each: the new name is the coordinate and the
 * old one stops being one; the NAME is what says the kind, so a suffixed rename restamps the
 * archetype and a suffix-less one leaves it alone; a rename is announced exactly once and only when
 * something changed; and three things are refused outright.
 *
 * <p><b>Nothing here asserts a call to the git host, because there is none to assert.</b> A bare is
 * keyed by the row's opaque id, so the {@code by-name} resolution below is the whole proof that the
 * rename took effect where it matters — that read is what qits-githost makes to turn {@code
 * /git/<project>/<name>} into the storage id it serves.
 */
@QuarkusTest
public class RepositoryRenameTest {

  /** Wins the port's injection over the shipped {@code @DefaultBean} announcer. */
  @Inject RecordingRepositoryAnnouncer announcer;

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId, String name, RepositoryArchetype archetype) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(null, name, archetype, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  private io.restassured.response.Response rename(String repoId, String newName) {
    return given()
        .contentType(ContentType.JSON)
        .body(new RepositoryController.RenameRepositoryRequest(newName))
        .when()
        .patch("/projects/api/repositories/" + repoId);
  }

  private io.restassured.response.Response resolveByName(String projectId, String name) {
    return given()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories/by-name/" + name);
  }

  // --- the happy path ---

  @Test
  public void renamingMakesTheNewNameTheCoordinateAndRetiresTheOld() {
    String projectId = createProject("Rename Happy");
    String repoId = createRepository(projectId, "checkout", RepositoryArchetype.SERVICE);

    rename(repoId, "payments-service")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", equalTo(repoId))
        .body("repository.name", equalTo("payments-service"))
        .body("previousName", equalTo("checkout"))
        .body("changed", equalTo(true));

    // The read qits-githost makes: the new name resolves to the same storage id, and the old name
    // resolves to nothing at all. A rename that left the old name answering is half a rename.
    resolveByName(projectId, "payments-service")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositoryId", equalTo(repoId));
    resolveByName(projectId, "checkout")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    // And the id still addresses the same row, which is the point of the identity ruling.
    given()
        .when()
        .get("/projects/api/repositories/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.name", equalTo("payments-service"));
  }

  /**
   * The freed name is really free — not merely unused by the row that vacated it. That is the half
   * of "every old alias goes" a client can observe, and the half that makes a swap of two names
   * expressible at all.
   */
  @Test
  public void theOldNameIsAvailableToAnotherRepositoryAfterwards() {
    String projectId = createProject("Rename Frees");
    String vacating = createRepository(projectId, "ledger", RepositoryArchetype.LIBRARY);
    String taking = createRepository(projectId, "postings", RepositoryArchetype.LIBRARY);

    rename(vacating, "ledger-javalib").then().statusCode(Response.Status.OK.getStatusCode());
    rename(taking, "ledger")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.name", equalTo("ledger"));

    resolveByName(projectId, "ledger")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositoryId", equalTo(taking));
    resolveByName(projectId, "ledger-javalib")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositoryId", equalTo(vacating));
  }

  // --- the name is the kind ---

  /**
   * The user's ruling, in one assertion: archetype is determined by name. A rename onto a name that
   * carries a role suffix restamps the row, whatever it was registered as before.
   */
  @Test
  public void aSuffixedNameRestampsTheArchetype() {
    String projectId = createProject("Rename Restamps");
    String repoId = createRepository(projectId, "mailer", RepositoryArchetype.SERVICE);

    rename(repoId, "mailer-daemon")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.archetype", equalTo("DAEMON"));

    rename(repoId, "mailer-jslib")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.archetype", equalTo("LIBRARY"));
  }

  /**
   * The other half, and the one that has to be deliberate: a name that declares no role says
   * nothing about the kind, which is a different statement from "this repository is nothing". The
   * stored archetype survives, because nothing here could correct a nulled one afterwards.
   */
  @Test
  public void aSuffixLessNameLeavesTheStoredArchetypeAlone() {
    String projectId = createProject("Rename Keeps");
    String repoId = createRepository(projectId, "shipping-frontend", RepositoryArchetype.FRONTEND);

    rename(repoId, "shipping")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.name", equalTo("shipping"))
        .body("repository.archetype", equalTo("FRONTEND"));
  }

  // --- the announcement ---

  @Test
  public void aRenameIsAnnouncedOnceWithBothNames() {
    String projectId = createProject("Rename Announced");
    String repoId = createRepository(projectId, "billing", RepositoryArchetype.SERVICE);
    announcer.clear();

    rename(repoId, "billing-service").then().statusCode(Response.Status.OK.getStatusCode());

    assertEquals(1, announcer.renamesOf(repoId).size(), "one rename, one announcement");
    RecordingRepositoryAnnouncer.Renamed announced = announcer.lastRenameOf(repoId).orElseThrow();
    assertEquals(projectId, announced.projectId());
    assertEquals("billing", announced.oldName());
    assertEquals("billing-service", announced.newName());
    assertTrue(announced.renamedAt() != null, "occurredAt is when the rename committed");
  }

  /** A rename to the name it already answers to changed nothing, so it announces nothing. */
  @Test
  public void renamingToTheCurrentNameIsANoOpAndAnnouncesNothing() {
    String projectId = createProject("Rename No-op");
    String repoId = createRepository(projectId, "inventory-service", RepositoryArchetype.SERVICE);
    announcer.clear();

    rename(repoId, "inventory-service")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.name", equalTo("inventory-service"))
        .body("previousName", equalTo("inventory-service"))
        .body("changed", equalTo(false));

    assertEquals(0, announcer.renamesOf(repoId).size(), "nothing happened, so nothing is said");
  }

  // --- refusals ---

  @Test
  public void aNameAnotherRepositoryAnswersToIsRefused() {
    String projectId = createProject("Rename Taken");
    createRepository(projectId, "taken", RepositoryArchetype.LIBRARY);
    String repoId = createRepository(projectId, "mover", RepositoryArchetype.SERVICE);
    announcer.clear();

    rename(repoId, "taken")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("already addresses"));

    // Refused means untouched: the row still answers to what it did, and nothing was announced.
    resolveByName(projectId, "mover")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositoryId", equalTo(repoId));
    assertEquals(0, announcer.renamesOf(repoId).size());
  }

  @Test
  public void anIllegalNameIsRefused() {
    String projectId = createProject("Rename Illegal");
    String repoId = createRepository(projectId, "shapes", RepositoryArchetype.SERVICE);

    for (String bad : new String[] {"has/slash", "-dashfirst", "with space"}) {
      rename(repoId, bad)
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
          .body("message", containsString("Invalid repository name"));
    }
    // A blank name never reaches the service: @NotBlank refuses it at the door.
    rename(repoId, "  ").then().statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  /**
   * The wrapper's name is {@code <slug>-<slug>} and the slug is immutable, so a renamed wrapper
   * would be addressable under a name its own project does not derive.
   */
  @Test
  public void theProjectsWrapperCannotBeRenamed() {
    String projectId = createProject("Rename Wrapper");
    String wrapperId =
        given()
            .when()
            .get("/projects/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("entries.find { it.repository.archetype == 'PROJECT' }.repository.id");

    rename(wrapperId, "something-else")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("wrapper repository"));
  }

  @Test
  public void renamingAnUnknownRepositoryIsA404() {
    rename("no-such-repository", "whatever-service")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  // --- what the rename deliberately does NOT do ---

  /**
   * The wrapper is not rewritten, and this is what that costs: the manifest still names the old
   * entry, so the row reads as undeclared until somebody pushes the {@code .gitmodules} change.
   * Asserted rather than merely documented, because it is the window the per-repo runbook orders
   * around — and because a future change that quietly started writing the wrapper would show up
   * here rather than in production.
   */
  @Test
  public void theWrapperStillNamesTheOldEntryUntilSomebodyPushesTheChange() {
    String projectId = createProject("Rename Wrapper Lag");
    String repoId = createRepository(projectId, "search", RepositoryArchetype.SERVICE);

    rename(repoId, "search-service").then().statusCode(Response.Status.OK.getStatusCode());

    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("wrapper.entries.name", hasItem("search"))
        .body("wrapper.entries.find { it.name == 'search' }.repositoryId", nullValue())
        .body("entries.find { it.repository.name == 'search-service' }.declared", equalTo(false));

    // And the reconcile says the same thing, in the words a person acts on.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/reconcile")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.repositoryId == '" + repoId + "' }.outcome", equalTo("UNDECLARED"))
        .body("entries.find { it.name == 'search' }.outcome", notNullValue());
  }
}
