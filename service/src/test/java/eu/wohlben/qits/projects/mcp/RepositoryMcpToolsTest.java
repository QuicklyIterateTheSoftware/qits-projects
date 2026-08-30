package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
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
 * Verifies the repository MCP server's per-connection project scoping: every tool resolves its
 * project from the {@code X-QITS-Project} header (never a tool argument), and refuses to act on a
 * repository outside that project — so a session can't reach across project boundaries.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class RepositoryMcpToolsTest {

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits-platform:admin,qits:system,qits-platform:system");
  }

  /** Isolate cloned repos in a temp dir, like the controller tests. */
  private final String fixtureUrl;

  public RepositoryMcpToolsTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

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
        .body(new ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  /**
   * A second repository needs its own name: a name addresses one repository per project, so cloning
   * the one fixture twice into a project would collide. A blank repository on the platform's own
   * host is the cheap way to a distinctly named sibling.
   */
  private String createBlankRepository(String projectId, String name) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(
                null, name, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
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
        .setStateless()
        .setMcpPath("/projects/mcp")
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
    String repoB = createBlankRepository(project, "narrowed-sibling");

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
    String repoB = createBlankRepository(project, "guarded-sibling");

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

  // SEAM (migration-plan.md §3.9): discoveryServerListsProjectsAndContextServers is not carried
  // over. It drove the DISCOVERY MCP server (listProjects / listContextServers), which lives in
  // service/src/main/java/eu/wohlben/qits/mcp — a monolith-only package no target receives. The
  // repository server this class otherwise tests is unaffected.
  /**
   * A client on the repository server carrying the {@code agentReadOnly=true} query-param marker an
   * autonomous (unattended, skip-permissions) launch stamps into its MCP URL.
   */
  private McpStreamableTestClient readOnlyClient(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp?" + ReadOnlyRepositoryToolFilter.READ_ONLY_PARAM + "=true")
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

  @Test
  public void readOnlyMarkerHidesEveryMutatingTool() {
    // An unattended read-only run (conflict resolution) attaches this server only for taskPrompt;
    // it must not be able to drive host-side mutations.
    String project = createProject("ReadOnly");
    readOnlyClient(project)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              for (String mutating :
                  java.util.List.of(
                      "createWorkspace",
                      "cleanupBranch",
                      "integrateBranch",
                      "mergeParentIntoWorkspace",
                      // The epic write tools (EpicMcpTools) are mutating too — an unattended run
                      // must not rewrite the project's plan.
                      "propose_epic",
                      "update_epic",
                      "add_feature",
                      "update_feature",
                      "remove_feature",
                      "add_task",
                      "update_task",
                      "remove_task")) {
                assertFalse(
                    names.contains(mutating),
                    "read-only run still exposes mutating tool " + mutating + ": " + names);
              }
              // The read-only tools stay available (the run still needs to inspect the repository).
              // taskPrompt is absent for a different reason — it needs workspace scope this
              // project-only client doesn't carry — so it isn't asserted here.
              assertTrue(
                  names.contains("listRepositories"), "read-only tool wrongly hidden: " + names);
              assertTrue(names.contains("list_epics"), "read-only tool wrongly hidden: " + names);
              assertTrue(names.contains("get_epic"), "read-only tool wrongly hidden: " + names);
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
    // The repository server must expose only the repository tools and the epic-refinement tools
    // that share its declared server name — nothing from other contexts — so the model stays on
    // task. The submodule tools are gone with the import they served: the wrapper's .gitmodules is
    // the project's manifest now, and it is read over REST.
    String project = createProject("Tools");
    client(project)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertEquals(
                  java.util.Set.of(
                      // SEAM (migration-plan.md §6): listWorkspaces, createWorkspace,
                      // cleanupBranch, integrateBranch and mergeParentIntoWorkspace were forwards
                      // to WorkspaceService and are cut with it (see RepositoryMcpTools). Still an
                      // exact-set assertion on purpose — that is what stops another context's
                      // tools leaking back onto this server.
                      "listRepositories",
                      "listBranches",
                      "listCommits",
                      "listCommitChanges",
                      "getCommitFileDiff",
                      // EpicMcpTools — the refinement surface, deliberately on the same declared
                      // server (a second name would need its own daemon-side contract). No
                      // transition tool: freezing a draft is a human act in the UI.
                      "list_epics",
                      "get_epic",
                      "propose_epic",
                      "update_epic",
                      "add_feature",
                      "update_feature",
                      "remove_feature",
                      "add_task",
                      "update_task",
                      "remove_task",
                      // RefinementDesignMcpTools — the frozen designs of a refinement. No resolve
                      // tool: accepting a proposal is a human act in the Design tab.
                      "list_designs",
                      "get_design",
                      "propose_design"),
                  java.util.Set.copyOf(names),
                  "unexpected tool surface: " + names);
            })
        .thenAssertResults();
  }
}
