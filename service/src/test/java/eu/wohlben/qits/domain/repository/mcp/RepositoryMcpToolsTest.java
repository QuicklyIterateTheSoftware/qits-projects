package eu.wohlben.qits.domain.repository.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.featureflow.control.RepositoryActionService;
import eu.wohlben.qits.domain.project.api.ProjectController;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.MultiMap;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies the repository MCP server's per-connection project scoping: every tool resolves its
 * project from the {@code X-QITS-Project} header (never a tool argument), and refuses to act on a
 * repository outside that project — so a session can't reach across project boundaries.
 */
@QuarkusTest
@TestProfile(RepositoryMcpToolsTest.TestProfile.class)
public class RepositoryMcpToolsTest {

  /** Isolate cloned repos in a temp dir, like the controller tests. */
  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-mcp-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private final String fixtureUrl;

  @Inject RepositoryActionService repositoryActionService;

  public RepositoryMcpToolsTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null))
        .when()
        .post("/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  /** All text content of a tool response joined — list tools emit one content item per element. */
  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  /** A streamable client on the unscoped discovery server ({@code /mcp}). */
  private McpStreamableTestClient discoveryClient() {
    return McpAssured.newStreamableClient().setMcpPath("/mcp").build().connect();
  }

  /** A streamable client on the repository server, scoped to {@code projectId} (or none). */
  private McpStreamableTestClient client(String projectId) {
    return client(projectId, null);
  }

  /**
   * A streamable client on the repository server, scoped to {@code projectId} and optionally
   * narrowed to {@code repositoryId} (pass null to leave the whole project in scope).
   */
  private McpStreamableTestClient client(String projectId, String repositoryId) {
    return McpAssured.newStreamableClient()
        .setMcpPath("/mcp/repository")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              if (repositoryId != null) {
                headers.add(ProjectScope.REPOSITORY_HEADER, repositoryId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  @Test
  public void listsOnlyTheScopedProjectsRepositories() {
    String project = createProject("Scoped");
    String repoId = createRepository(project);

    client(project)
        .when()
        .toolsCall(
            "listRepositories",
            Map.of(),
            response -> {
              assertFalse(response.isError(), "a scoped session should resolve its project");
              String text = text(response);
              assertTrue(text.contains(repoId), "should list the project's repository: " + text);
            })
        .thenAssertResults();
  }

  @Test
  public void rejectsToolCallsWithoutAProjectHeader() {
    client(null)
        .when()
        .toolsCall(
            "listRepositories",
            Map.of(),
            response -> {
              assertTrue(response.isError(), "an unscoped session must not resolve a project");
              assertTrue(text(response).contains("not scoped to a project"));
            })
        .thenAssertResults();
  }

  @Test
  public void refusesRepositoriesOutsideTheScopedProject() {
    String projectA = createProject("A");
    String repoInA = createRepository(projectA);
    String projectB = createProject("B");

    // A session scoped to B may not touch a repository that belongs to A.
    client(projectB)
        .when()
        .toolsCall(
            "listBranches",
            Map.of("repoId", repoInA),
            response -> {
              assertTrue(response.isError(), "cross-project access must be refused");
              assertTrue(text(response).contains("not found in this project"));
            })
        .thenAssertResults();
  }

  @Test
  public void narrowsToTheScopedRepositoryWhenRepositoryHeaderIsSet() {
    String project = createProject("Narrowed");
    String repoA = createRepository(project);
    String repoB = createRepository(project);

    // listRepositories returns only the narrowed repo, even though both belong to the project.
    client(project, repoA)
        .when()
        .toolsCall(
            "listRepositories",
            Map.of(),
            response -> {
              assertFalse(response.isError());
              String text = text(response);
              assertTrue(text.contains(repoA), "should list the scoped repo: " + text);
              assertFalse(text.contains(repoB), "must hide the sibling repo: " + text);
            })
        .thenAssertResults();
  }

  @Test
  public void refusesSiblingRepositoriesWhenNarrowed() {
    String project = createProject("NarrowedGuard");
    String repoA = createRepository(project);
    String repoB = createRepository(project);

    // A session narrowed to repoA may not touch repoB, even though it is in the same project.
    client(project, repoA)
        .when()
        .toolsCall(
            "listBranches",
            Map.of("repoId", repoB),
            response -> {
              assertTrue(response.isError(), "out-of-scope repo access must be refused");
              assertTrue(text(response).contains("not in this session's scope"), text(response));
            })
        .thenAssertResults();
  }

  @Test
  public void discoveryServerListsProjectsAndContextServers() {
    String project = createProject("Discoverable");

    discoveryClient()
        .when()
        .toolsCall(
            "listProjects",
            Map.of(),
            response -> {
              assertFalse(response.isError());
              assertTrue(
                  text(response).contains(project),
                  "discovery should list the project to scope with");
            })
        .toolsCall(
            "listContextServers",
            Map.of(),
            response -> {
              assertFalse(response.isError());
              String text = text(response);
              assertTrue(text.contains("repository"), "should advertise the repository server");
              assertTrue(text.contains("/mcp/repository"), "should advertise its path: " + text);
              assertTrue(
                  text.contains(ProjectScope.PROJECT_HEADER),
                  "should advertise the scope header: " + text);
            })
        .thenAssertResults();
  }

  @Test
  public void discoveryToolsAreNotExposedOnTheRepositoryServer() {
    // The discovery tools live only on the default server; the repository server stays focused.
    String project = createProject("Focused");
    client(project)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertFalse(names.contains("listProjects"), "leaked discovery tool: " + names);
              assertFalse(names.contains("listContextServers"), "leaked discovery tool: " + names);
            })
        .thenAssertResults();
  }

  @Test
  public void exposesExactlyTheRepositoryContextToolset() {
    // The repository server must expose only the repository tools — nothing from other contexts —
    // so the model stays on task. Daemons are repository-owned, so their management tools (see
    // DaemonMcpTools) belong to this surface.
    String project = createProject("Tools");
    client(project)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertEquals(
                  java.util.Set.of(
                      "listRepositories",
                      "listBranches",
                      "listWorkspaces",
                      "listCommits",
                      "listCommitChanges",
                      "getCommitFileDiff",
                      "createWorkspace",
                      "cleanupBranch",
                      "integrateBranch",
                      "mergeParentIntoWorkspace",
                      "listActions",
                      "runAction",
                      "listDaemons",
                      "createDaemon",
                      "updateDaemon",
                      "deleteDaemon",
                      "listWorkspaceDaemons",
                      "startDaemon",
                      "stopDaemon"),
                  java.util.Set.copyOf(names),
                  "unexpected tool surface: " + names);
            })
        .thenAssertResults();
  }

  // --- Run actions ----------------------------------------------------------

  private String createWorkspace(String repoId, String workspaceId) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("id", workspaceId, "parent", "master"))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("workspace.workspaceId");
  }

  private String createAction(String name, String executeScript, boolean interactive) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "executeScript", executeScript, "interactive", interactive))
        .when()
        .post("/api/action-configurations")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("actionConfiguration.id");
  }

  @Test
  public void runsANonInteractiveActionInTheWorkspace() {
    String project = createProject("Runner");
    String repoId = createRepository(project);
    String workspaceId = createWorkspace(repoId, "run-wt");
    String actionId = createAction("Echo", "echo HELLO_FROM_ACTION", false);

    client(project)
        .when()
        .toolsCall(
            "runAction",
            Map.of("repoId", repoId, "workspaceId", workspaceId, "actionId", actionId),
            response -> {
              assertFalse(response.isError(), "run should succeed: " + text(response));
              String text = text(response);
              assertTrue(text.contains("HELLO_FROM_ACTION"), "should capture output: " + text);
            })
        .thenAssertResults();
  }

  @Test
  public void refusesInteractiveActions() {
    String project = createProject("NoInteractive");
    String repoId = createRepository(project);
    String workspaceId = createWorkspace(repoId, "i-wt");
    String actionId = createAction("Shell", "exec bash", true);

    client(project)
        .when()
        .toolsCall(
            "runAction",
            Map.of("repoId", repoId, "workspaceId", workspaceId, "actionId", actionId),
            response -> {
              assertTrue(response.isError(), "interactive actions must be refused");
              assertTrue(text(response).contains("interactive"), text(response));
            })
        .thenAssertResults();
  }

  @Test
  public void listActionsExcludesInteractiveOnes() {
    String project = createProject("Listing");
    createAction("Run Tests XYZ", "mvn test", false);
    createAction("Interactive Shell XYZ", "exec bash", true);

    client(project)
        .when()
        .toolsCall(
            "listActions",
            Map.of(),
            response -> {
              assertFalse(response.isError());
              String text = text(response);
              assertTrue(text.contains("Run Tests XYZ"), "should list non-interactive: " + text);
              assertFalse(
                  text.contains("Interactive Shell XYZ"), "must exclude interactive: " + text);
            })
        .thenAssertResults();
  }

  @Test
  public void runsARepositoryScopedActionInItsOwnRepository() {
    String project = createProject("RepoRunner");
    String repoId = createRepository(project);
    String workspaceId = createWorkspace(repoId, "repo-run-wt");
    // a repository-owned action (seeded directly via the service — there is no global REST for it)
    var action =
        repositoryActionService.create(
            repoId, "Repo Echo", null, "echo REPO_ACTION_RAN", null, false, null);

    client(project)
        .when()
        .toolsCall(
            "runAction",
            Map.of("repoId", repoId, "workspaceId", workspaceId, "actionId", action.id),
            response -> {
              assertFalse(response.isError(), "repo-scoped run should succeed: " + text(response));
              assertTrue(text(response).contains("REPO_ACTION_RAN"), text(response));
            })
        .thenAssertResults();
  }
}
