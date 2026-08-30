package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.api.EpicController;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import io.restassured.http.ContentType;
import io.vertx.core.MultiMap;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The epic-refinement MCP surface: its per-connection project scoping, and the freeze coming back
 * as a readable tool error rather than as a protocol error. The lifecycle rules themselves are
 * pinned in the epics module; what is tested here is what the agent on the other end of the socket
 * actually experiences.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class EpicMcpToolsTest {

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits-platform:admin,qits:system,qits-platform:system");
  }

  private final String fixtureUrl;

  public EpicMcpToolsTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  // --- Fixtures over REST ---------------------------------------------------

  private String createProject(String name) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  /** Freeze an epic's scope the way the UI does — the only thing the agent cannot do itself. */
  private void freeze(String epicId) {
    authenticated()
        .contentType(ContentType.JSON)
        .body(new EpicController.TransitionEpicRequest("IMPLEMENTATION"))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  // --- MCP plumbing ---------------------------------------------------------

  /** All text content of a tool response joined — list tools emit one content item per element. */
  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  /** A streamable client on the repository server, scoped to {@code projectId} (or none). */
  private McpStreamableTestClient client(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  /** Call one tool and hand its response to {@code check}. */
  private void call(String projectId, String tool, Map<String, Object> args, Check check) {
    client(projectId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  /** The single-tool assertion shape, so a call site reads as one statement. */
  private interface Check {
    void accept(ToolResponse response);
  }

  /** Propose an epic through the tools and return its id. */
  private String proposeEpic(String projectId, String title) {
    String[] id = new String[1];
    call(
        projectId,
        "propose_epic",
        Map.of("title", title, "description", "drafted by the agent"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("\"REFINING\""), "a proposed epic must be a draft: " + body);
          id[0] = idIn(body);
        });
    return id[0];
  }

  /** The {@code "id"} field of a tool's JSON result — the first one, which is the row's own. */
  private static String idIn(String json) {
    int at = json.indexOf("\"id\"");
    int open = json.indexOf('"', json.indexOf(':', at) + 1);
    return json.substring(open + 1, json.indexOf('"', open + 1));
  }

  // --- Scoping --------------------------------------------------------------

  @Test
  public void rejectsEpicToolCallsWithoutAProjectHeader() {
    call(
        null,
        "list_epics",
        Map.of(),
        response -> {
          assertTrue(response.isError(), "an unscoped session must not resolve a project");
          assertTrue(text(response).contains("not scoped to a project"));
        });
  }

  @Test
  public void listsOnlyTheScopedProjectsEpics() {
    String projectA = createProject("Epics A");
    String projectB = createProject("Epics B");
    String inA = proposeEpic(projectA, "Only in A");
    String inB = proposeEpic(projectB, "Only in B");

    call(
        projectA,
        "list_epics",
        Map.of(),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(inA), "should list its own epic: " + body);
          assertFalse(body.contains(inB), "must not leak the other project's epic: " + body);
        });
  }

  @Test
  public void refusesAnEpicOutsideTheScopedProject() {
    String projectA = createProject("Owner");
    String epicInA = proposeEpic(projectA, "Owned");
    String projectB = createProject("Stranger");

    call(
        projectB,
        "get_epic",
        Map.of("id", epicInA),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- Drafting -------------------------------------------------------------

  @Test
  public void proposesADraftAndFillsInItsTree() {
    String projectId = createProject("Refinery");
    String repoId = createRepository(projectId);
    String epicId = proposeEpic(projectId, "Planning domain");

    String[] featureId = new String[1];
    call(
        projectId,
        "add_feature",
        Map.of("epicId", epicId, "title", "Lifecycle", "description", "statuses"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    call(
        projectId,
        "add_task",
        Map.of(
            "featureId", featureId[0],
            "repositoryId", repoId,
            "title", "Add the status column",
            "description", "V3"),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "get_epic",
        Map.of("id", epicId),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("Planning domain"), body);
          assertTrue(body.contains("Lifecycle"), "the tree must carry its feature: " + body);
          assertTrue(
              body.contains("Add the status column"), "the tree must carry its task: " + body);
        });
  }

  @Test
  public void filtersTheListByStatus() {
    String projectId = createProject("Filtered");
    String draft = proposeEpic(projectId, "Still drafting");
    String frozen = proposeEpic(projectId, "Under way");
    freeze(frozen);

    call(
        projectId,
        "list_epics",
        Map.of("status", "REFINING"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(draft), "the draft is what the filter is for: " + body);
          assertFalse(body.contains(frozen), "a frozen epic is not a draft: " + body);
        });
  }

  @Test
  public void reportsAnUnknownStatusFilterAsAToolError() {
    String projectId = createProject("Typo");
    call(
        projectId,
        "list_epics",
        Map.of("status", "REFINEING"),
        response -> {
          assertTrue(response.isError(), "a typo must not read as 'no epics'");
          assertTrue(text(response).contains("Unknown epic status"), text(response));
        });
  }

  // --- The freeze -----------------------------------------------------------

  @Test
  public void refusesToEditAFrozenEpicWithAReadableToolError() {
    String projectId = createProject("Frozen");
    String epicId = proposeEpic(projectId, "Shipped scope");
    freeze(epicId);

    // isError, NOT a JSON-RPC protocol error: the model has to be able to read the refusal and
    // move on (propose a new epic) inside the same turn.
    call(
        projectId,
        "update_epic",
        Map.of("id", epicId, "title", "Second thoughts"),
        response -> {
          assertTrue(response.isError(), "a frozen epic must refuse a structural edit");
          assertTrue(text(response).contains("frozen"), text(response));
        });
  }

  @Test
  public void refusesToAddAFeatureToAFrozenEpic() {
    String projectId = createProject("FrozenTree");
    String epicId = proposeEpic(projectId, "Shipped scope");
    freeze(epicId);

    call(
        projectId,
        "add_feature",
        Map.of("epicId", epicId, "title", "Late idea"),
        response -> {
          assertTrue(response.isError(), "a frozen epic must refuse a new feature");
          assertTrue(text(response).contains("frozen"), text(response));
        });
  }

  // --- Cross-boundary checks -----------------------------------------------

  @Test
  public void refusesATaskBoundToAnotherProjectsRepository() {
    String projectA = createProject("Planner");
    String projectB = createProject("Elsewhere");
    String foreignRepo = createRepository(projectB);
    String epicId = proposeEpic(projectA, "Cross-check");

    String[] featureId = new String[1];
    call(
        projectA,
        "add_feature",
        Map.of("epicId", epicId, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    call(
        projectA,
        "add_task",
        Map.of(
            "featureId", featureId[0],
            "repositoryId", foreignRepo,
            "title", "Should not bind"),
        response -> {
          assertTrue(response.isError(), "a task must not bind a foreign repository");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  @Test
  public void refusesAFeatureOfAnotherProjectsEpic() {
    String projectA = createProject("Home");
    String epicInA = proposeEpic(projectA, "Owned");
    String projectB = createProject("Intruder");

    String[] featureId = new String[1];
    call(
        projectA,
        "add_feature",
        Map.of("epicId", epicInA, "title", "Slice"),
        response -> {
          assertFalse(response.isError(), text(response));
          featureId[0] = idIn(text(response));
        });

    // The feature id alone must not be a way past the scope: it is checked back to its epic.
    call(
        projectB,
        "remove_feature",
        Map.of("id", featureId[0]),
        response -> {
          assertTrue(response.isError(), "cross-project feature removal must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- The surface ----------------------------------------------------------

  @Test
  public void exposesNoTransitionTool() {
    // Freezing a draft is a human act in the UI. Nothing on this server may move a status.
    String projectId = createProject("NoFreeze");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertFalse(names.contains("transition_epic"), names.toString());
              assertFalse(names.contains("supersede_epic"), names.toString());
            })
        .thenAssertResults();
  }
}
