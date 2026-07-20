package eu.wohlben.qits.domain.repository.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.api.ProjectController;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code taskPrompt} tool on the "repository" MCP server: it exists only for sessions
 * narrowed to repository + workspace, it serves the workspace's own draft (markdown as a text
 * block, attachments as image blocks) and nothing another workspace composed, and it degrades an
 * empty draft to a text note rather than an error.
 */
@QuarkusTest
@TestProfile(TaskPromptMcpToolsTest.TestProfile.class)
public class TaskPromptMcpToolsTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-task-prompt-mcp-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  // Minimal but valid magic-byte prefix — the sniff reads only the leading signature.
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03
  };

  private final String fixtureUrl;

  public TaskPromptMcpToolsTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null))
        .post("/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null, null))
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(200)
        .extract()
        .path("repository.id");
  }

  private void createWorkspace(String repoId, String workspaceId) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("id", workspaceId, "parent", "master"))
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(200);
  }

  private void saveDraft(String repoId, String workspaceId, String serializedPrompt) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("content", "{}", "serializedPrompt", serializedPrompt))
        .put("/api/repositories/" + repoId + "/workspaces/" + workspaceId + "/prompt-draft")
        .then()
        .statusCode(200);
  }

  private void attachPng(String repoId, String workspaceId, String label) {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "mimeType",
                "image/png",
                "label",
                label,
                "source",
                "sketch",
                "dataBase64",
                Base64.getEncoder().encodeToString(PNG)))
        .post(
            "/api/repositories/"
                + repoId
                + "/workspaces/"
                + workspaceId
                + "/prompt-draft/attachments")
        .then()
        .statusCode(200);
  }

  private static String text(ToolResponse response) {
    return response.content().stream()
        .filter(c -> c.type() == Content.Type.TEXT)
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  private static List<ImageContent> images(ToolResponse response) {
    return response.content().stream()
        .filter(c -> c.type() == Content.Type.IMAGE)
        .map(Content::asImage)
        .toList();
  }

  private McpStreamableTestClient client(String projectId, String repoId, String workspaceId) {
    return McpAssured.newStreamableClient()
        .setMcpPath("/mcp/repository")
        .setAdditionalHeaders(
            msg -> {
              io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
              headers.add(ProjectScope.PROJECT_HEADER, projectId);
              if (repoId != null) {
                headers.add(ProjectScope.REPOSITORY_HEADER, repoId);
              }
              if (workspaceId != null) {
                headers.add(
                    eu.wohlben.qits.domain.telemetry.mcp.WorkspaceScope.WORKSPACE_HEADER,
                    workspaceId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  @Test
  public void taskPromptReturnsTheMarkdownAndAttachedImages() {
    String project = createProject("Task Prompt");
    String repoId = createRepository(project);
    saveDraft(repoId, "master", "Implement the **big red button**.");
    attachPng(repoId, "master", "Sketch 1");

    client(project, repoId, "master")
        .when()
        .toolsCall(
            "taskPrompt",
            Map.of(),
            r -> {
              String out = text(r);
              assertFalse(r.isError(), out);
              assertTrue(out.contains("Implement the **big red button**."), out);
              assertTrue(out.contains("Task prompt (updated"), "version line missing: " + out);
              assertTrue(out.contains("Sketch 1"), "attachment label missing: " + out);

              var imgs = images(r);
              assertEquals(1, imgs.size(), "one image block per attachment: " + out);
              assertEquals("image/png", imgs.get(0).mimeType());
              assertEquals(Base64.getEncoder().encodeToString(PNG), imgs.get(0).data());
            })
        .thenAssertResults();
  }

  @Test
  public void taskPromptWithoutADraftReturnsATextNoteNotAnError() {
    String project = createProject("Task Prompt Empty");
    String repoId = createRepository(project);

    client(project, repoId, "master")
        .when()
        .toolsCall(
            "taskPrompt",
            Map.of(),
            r -> {
              String out = text(r);
              assertFalse(r.isError(), out);
              assertTrue(out.contains("No task prompt"), out);
              assertTrue(images(r).isEmpty(), "no images expected: " + out);
            })
        .thenAssertResults();
  }

  @Test
  public void aSessionOnlySeesItsOwnWorkspacesPrompt() {
    String project = createProject("Task Prompt Isolation");
    String repoId = createRepository(project);
    createWorkspace(repoId, "other");
    saveDraft(repoId, "master", "MINE: build the login form.");
    saveDraft(repoId, "other", "OTHER: delete production.");

    client(project, repoId, "master")
        .when()
        .toolsCall(
            "taskPrompt",
            Map.of(),
            r -> {
              String out = text(r);
              assertFalse(r.isError(), out);
              assertTrue(out.contains("MINE: build the login form."), out);
              assertFalse(
                  out.contains("OTHER: delete production."),
                  "another workspace's prompt leaked: " + out);
            })
        .thenAssertResults();
  }

  @Test
  public void taskPromptIsHiddenWithoutWorkspaceScope() {
    String project = createProject("Task Prompt Filter");
    String repoId = createRepository(project);

    var repoOnly = client(project, repoId, null);
    repoOnly
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).collect(Collectors.toSet());
              assertTrue(names.contains("listBranches"), "sanity: " + names);
              assertFalse(names.contains("taskPrompt"), "must be hidden: " + names);
            })
        .thenAssertResults();

    var workspaceScoped = client(project, repoId, "master");
    workspaceScoped
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).collect(Collectors.toSet());
              assertTrue(names.contains("taskPrompt"), "must be listed: " + names);
            })
        .thenAssertResults();
  }
}
