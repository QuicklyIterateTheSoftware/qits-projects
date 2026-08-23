package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.persistence.RefinementDesignRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementDesigns;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The refinement designs REST surface: capture, list, read, both resolutions of a proposal, the
 * size cap, the per-refinement scoping, and the cascade a discard leaves behind.
 *
 * <p>Proposals are made through {@link RefinementDesigns} rather than over REST, because that is
 * where they come from in life: the REST POST is a person capturing a page and is always ACTIVE,
 * and only the MCP tool proposes.
 */
@QuarkusTest
public class RefinementDesignControllerTest {

  @Inject RefinementDesignRepository store;

  @Inject RefinementDesigns designs;

  private static final String DOC =
      "<!doctype html><html><body style=\"margin:0\">Checkout</body></html>";

  private static final String REVISED =
      "<!doctype html><html><body style=\"margin:0\">Checkout, roomier</body></html>";

  // --- Fixtures -------------------------------------------------------------

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
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
        .body(Map.of("title", title, "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  /** A refinement of a fresh epic in a fresh project — the row every design hangs off. */
  private long openRefinement(String name) {
    String projectId = createProject(name);
    String epicId = createEpic(projectId, name + " Epic");
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("epicId", epicId))
            .when()
            .post("/projects/api/refinements")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  private static String base(long refinementId) {
    return "/projects/api/refinements/" + refinementId + "/designs";
  }

  private String capture(long refinementId, String title, String html) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "html", html, "sourceRoute", "/checkout", "truncated", false))
        .when()
        .post(base(refinementId))
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private String resolve(long refinementId, String designId, String mode, int expected) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("mode", mode))
        .when()
        .post(base(refinementId) + "/" + designId + "/resolve")
        .then()
        .statusCode(expected)
        .extract()
        .asString();
  }

  private static String htmlOf(long refinementId, String designId) {
    return given()
        .when()
        .get(base(refinementId) + "/" + designId)
        .then()
        .statusCode(200)
        .extract()
        .path("html");
  }

  // --- Capture and read -----------------------------------------------------

  @Test
  public void aCapturedDesignIsActiveAndCarriesItsDocumentOnlyOnTheSingleRead() {
    long id = openRefinement("Design Capture");

    String designId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "Checkout", "html", DOC, "truncated", false))
            .when()
            .post(base(id))
            .then()
            .statusCode(201)
            .body("status", equalTo("ACTIVE"))
            .body("htmlBytes", equalTo(DOC.length()))
            .body("createdBy", notNullValue())
            .body("html", nullValue())
            .extract()
            .path("id");

    given()
        .when()
        .get(base(id))
        .then()
        .statusCode(200)
        .body("designs[0].id", equalTo(designId))
        .body("designs[0].title", equalTo("Checkout"))
        .body("designs[0].html", nullValue());

    given()
        .when()
        .get(base(id) + "/" + designId)
        .then()
        .statusCode(200)
        .body("html", equalTo(DOC));
  }

  @Test
  public void aDesignIsRenamedAndDeleted() {
    long id = openRefinement("Design Rename");
    String designId = capture(id, "Draft", DOC);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Checkout, second pass"))
        .when()
        .put(base(id) + "/" + designId)
        .then()
        .statusCode(200)
        .body("title", equalTo("Checkout, second pass"));

    given().when().delete(base(id) + "/" + designId).then().statusCode(204);
    given().when().get(base(id) + "/" + designId).then().statusCode(404);
  }

  // --- Proposals ------------------------------------------------------------

  @Test
  public void replacingAProposalOverwritesTheOriginalAndTakesTheProposalAway() {
    long id = openRefinement("Design Replace");
    String original = capture(id, "Checkout", DOC);
    RefinementDesign proposal =
        designs.propose(id, "Roomier", REVISED, "The summary needed air.", original, "mcp-agent");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("mode", "REPLACE"))
        .when()
        .post(base(id) + "/" + proposal.id + "/resolve")
        .then()
        .statusCode(200)
        .body("id", equalTo(original))
        .body("status", equalTo("ACTIVE"));

    assertTrue(REVISED.equals(htmlOf(id, original)), "the original must carry the revision");
    given().when().get(base(id) + "/" + proposal.id).then().statusCode(404);
  }

  @Test
  public void keepingAProposalMakesItADesignOfItsOwn() {
    long id = openRefinement("Design Keep");
    String original = capture(id, "Checkout", DOC);
    RefinementDesign proposal =
        designs.propose(id, "A second take", REVISED, "Worth having both.", original, "mcp-agent");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("mode", "KEEP"))
        .when()
        .post(base(id) + "/" + proposal.id + "/resolve")
        .then()
        .statusCode(200)
        .body("id", equalTo(proposal.id))
        .body("status", equalTo("ACTIVE"))
        .body("note", nullValue());

    // The original is untouched: keeping is not replacing.
    assertTrue(DOC.equals(htmlOf(id, original)), "the original must be left alone");
  }

  @Test
  public void resolvingSomethingThatIsNotAProposalIsRefused() {
    long id = openRefinement("Design Settled");
    String designId = capture(id, "Checkout", DOC);
    resolve(id, designId, "KEEP", 409);
  }

  @Test
  public void anUnknownResolutionIsRejected() {
    long id = openRefinement("Design Mode");
    String designId = capture(id, "Checkout", DOC);
    resolve(id, designId, "ACCEPT", 400);
  }

  // --- Limits and scoping ---------------------------------------------------

  @Test
  public void aDocumentOverTheCapIsRefused() {
    long id = openRefinement("Design Huge");
    String huge = "x".repeat(5 * 1024 * 1024);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Too much", "html", huge, "truncated", false))
        .when()
        .post(base(id))
        .then()
        .statusCode(413);
  }

  @Test
  public void aDesignOfAnotherRefinementIsNotFound() {
    long mine = openRefinement("Design Mine");
    long theirs = openRefinement("Design Theirs");
    String designId = capture(mine, "Checkout", DOC);

    given().when().get(base(theirs) + "/" + designId).then().statusCode(404);
    given().when().delete(base(theirs) + "/" + designId).then().statusCode(404);
  }

  @Test
  public void discardingTheRefinementTakesItsDesignsWithIt() {
    long id = openRefinement("Design Discard");
    String designId = capture(id, "Checkout", DOC);

    given()
        .when()
        .post("/projects/api/refinements/" + id + "/discard")
        .then()
        .statusCode(200)
        .body("success", equalTo(true));

    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> store.findByIdOptional(designId)).isEmpty(),
        "the design must go with the refinement it hangs off");
  }
}
