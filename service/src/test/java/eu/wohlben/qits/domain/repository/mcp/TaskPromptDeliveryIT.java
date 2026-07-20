package eu.wohlben.qits.domain.repository.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The acceptance gate for the push→fetch delivery mechanism: against the <em>real</em> Java {@code
 * taskPrompt} tool served over the "repository" MCP server, a Claude Code run seeded only with the
 * one-sentence bootstrap turn fetches the workspace's composed prompt and reads back
 * <strong>both</strong> a text nonce (from the serialized-prompt text block) and an in-image nonce
 * (from an attached PNG, which is only readable if the client converted the MCP {@code
 * ImageContent} block into a native image the model can see). This is the committed replacement for
 * the throwaway 2026-07-20 spike.
 *
 * <p>{@link QuarkusIntegrationTest} launches the built artifact on a fixed port, so the real MCP
 * server (not an in-JVM {@code McpAssured} client) answers, and a real {@code claude} container
 * reaches it over HTTP exactly as a workspace container would. Part of the
 * <strong>extended</strong> suite ({@code ./mvnw verify -Pextended}); self-skips when docker, the
 * {@code qits/workspace} image, a signed-in shared claude volume, or container→host reachability is
 * absent. Build the image first: {@code docker build -t qits/workspace --target workspace -f
 * docker/qits/Dockerfile .}
 *
 * <p>Two legs: {@code claude -p} (print) and the interactive TUI via tmux (the must-have PTY path).
 * The tmux leg additionally self-skips when tmux is not in the image.
 */
@QuarkusIntegrationTest
@TestProfile(TaskPromptDeliveryIT.Profile.class)
@Tag("extended")
public class TaskPromptDeliveryIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String VOLUME =
      System.getProperty("qits.workspace.claude-volume", "qits_shared_dot_claude");
  private static final String MOUNT = "/claude-home";
  private static final int PORT = 8080;
  // The host a container uses to reach the app, resolved exactly as the app does
  // (host.docker.internal
  // on plain Linux, the WSL2 eth0 IP on WSL2), so no -D override is needed on either environment.
  private static final String GIT_HOST =
      eu.wohlben.qits.domain.repository.control.QitsHostResolver.resolve(
          System.getProperty("qits.workspace.git-host", "auto"));

  // Nonces that exist ONLY in the composed prompt: TEXT in the serialized-prompt text block, IMAGE
  // rendered into the attached PNG. The model can echo them back only by fetching + reading the
  // tool
  // result — TEXT proves the text block arrived, IMAGE proves ImageContent became a native image.
  private static final String TEXT_NONCE = "TASKPROMPTTEXT7731";
  private static final String IMAGE_NONCE = "SKETCHIMAGE4242";

  private static final String BOOTSTRAP =
      "Fetch the current task prompt for this workspace with the taskPrompt tool, then do exactly"
          + " what it says.";

  /** Fixed port + throwaway data dir / in-memory DB, so the container reaches a known endpoint. */
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path dataDir = Files.createTempDirectory("qits-task-prompt-delivery-it");
        Map<String, String> overrides = new java.util.HashMap<>();
        overrides.put("quarkus.http.port", String.valueOf(PORT));
        overrides.put("quarkus.http.test-port", String.valueOf(PORT));
        overrides.put("qits.workspace.qits-port", String.valueOf(PORT));
        overrides.put("qits.repositories.data-dir", dataDir.toString());
        overrides.put(
            "quarkus.datasource.jdbc.url", "jdbc:h2:mem:task-prompt-delivery-it;DB_CLOSE_DELAY=-1");
        overrides.put("qits.speech.warmup-on-start", "false");
        overrides.put("qits.workspace.git-host", GIT_HOST);
        return overrides;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private record ExecResult(int exitCode, String output) {}

  // ---- test bodies -----------------------------------------------------------------------------

  @Test
  public void printModeFetchesTheTaskPromptAndReadsBackTextAndImageNonces() throws Exception {
    assumeTrue(backendAvailable(), "docker + " + IMAGE + " + signed-in claude volume required");
    assumeTrue(
        containerCanReachApp(), "a container must be able to reach " + GIT_HOST + ":" + PORT);

    String mcpConfig = seedWorkspaceWithComposedPrompt();

    List<String> argv = new ArrayList<>(baseClaudeRun());
    argv.addAll(
        List.of(
            IMAGE,
            "claude",
            "-p",
            BOOTSTRAP,
            "--strict-mcp-config",
            "--mcp-config",
            mcpConfig,
            "--allowedTools",
            "mcp__repository__taskPrompt",
            "--dangerously-skip-permissions"));
    ExecResult run = runCapture(argv, 300);

    assertTrue(
        run.output().contains(TEXT_NONCE),
        "the model must echo the text-block nonce (the prompt was not fetched): " + run.output());
    assertTrue(
        run.output().contains(IMAGE_NONCE),
        "the model must echo the in-image nonce (ImageContent was not rendered): " + run.output());
  }

  @Test
  public void interactiveTuiFetchesTheTaskPromptAndReadsBackTextAndImageNonces() throws Exception {
    assumeTrue(backendAvailable(), "docker + " + IMAGE + " + signed-in claude volume required");
    assumeTrue(
        containerCanReachApp(), "a container must be able to reach " + GIT_HOST + ":" + PORT);
    assumeTrue(tmuxAvailable(), "tmux must be present in " + IMAGE + " for the PTY leg");

    String mcpConfig = seedWorkspaceWithComposedPrompt();
    String container = "qits-taskprompt-it-" + UUID.randomUUID().toString().substring(0, 8);

    try {
      // Keep a container alive to host the TUI; run the interactive claude in a detached tmux pane
      // with the bootstrap as its argv prompt (exactly how AgentLaunchService.renderInteractive
      // embeds it). Pre-allowing the tool avoids the interposed permission dialog.
      List<String> up = new ArrayList<>(List.of("docker", "run", "-d", "--name", container));
      up.addAll(runFlags());
      up.addAll(List.of(IMAGE, "sleep", "600"));
      assertExit0(runCapture(up, 60), "start container");

      String claudeCmd =
          "claude "
              + shSingleQuote(BOOTSTRAP)
              + " --strict-mcp-config --mcp-config "
              + shSingleQuote(mcpConfig)
              + " --allowedTools mcp__repository__taskPrompt --dangerously-skip-permissions";
      runCapture(
          List.of(
              "docker",
              "exec",
              container,
              "tmux",
              "new-session",
              "-d",
              "-s",
              "s",
              "-x",
              "220",
              "-y",
              "50",
              claudeCmd),
          30);

      // Poll the pane until both nonces appear or we time out — the TUI renders asynchronously.
      String pane = "";
      long deadline = System.currentTimeMillis() + 180_000;
      while (System.currentTimeMillis() < deadline) {
        pane =
            runCapture(
                    List.of("docker", "exec", container, "tmux", "capture-pane", "-p", "-t", "s"),
                    30)
                .output();
        if (pane.contains(TEXT_NONCE) && pane.contains(IMAGE_NONCE)) {
          break;
        }
        Thread.sleep(3000);
      }

      assertTrue(pane.contains(TEXT_NONCE), "the TUI must echo the text-block nonce: " + pane);
      assertTrue(pane.contains(IMAGE_NONCE), "the TUI must echo the in-image nonce: " + pane);
    } finally {
      runCapture(List.of("docker", "rm", "-f", container), 30);
    }
  }

  // ---- seeding ---------------------------------------------------------------------------------

  /**
   * Seeds a project + repository (its master workspace) and composes a prompt whose serialized text
   * carries {@link #TEXT_NONCE} and whose one attachment is a PNG showing {@link #IMAGE_NONCE}.
   * Returns the {@code --mcp-config} JSON pointing a workspace-scoped "repository" server at the
   * app.
   */
  private String seedWorkspaceWithComposedPrompt() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();

    String projectId =
        given()
            .header("Remote-User", "it")
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Task Prompt Delivery IT", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    String repoId =
        given()
            .header("Remote-User", "it")
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController
                    .CreateProjectRepositoryRequest(fixtureUrl, null, null))
            .when()
            .post("/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    String task =
        "Your task has two steps and no others. Step 1: output the exact token "
            + TEXT_NONCE
            + ". Step 2: look at the attached image and output the exact word shown in it. Output"
            + " only those two tokens, each on its own line, and nothing else.";
    given()
        .header("Remote-User", "it")
        .contentType(ContentType.JSON)
        .body(Map.of("content", "{}", "serializedPrompt", task))
        .when()
        .put("/api/repositories/" + repoId + "/workspaces/master/prompt-draft")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .header("Remote-User", "it")
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "mimeType",
                "image/png",
                "label",
                "Sketch 1",
                "source",
                "sketch",
                "dataBase64",
                Base64.getEncoder().encodeToString(noncePng(IMAGE_NONCE))))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/master/prompt-draft/attachments")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    String url =
        "http://"
            + GIT_HOST
            + ":"
            + PORT
            + "/mcp/repository?projectId="
            + projectId
            + "&repositoryId="
            + repoId
            + "&workspaceId=master";
    return "{\"mcpServers\":{\"repository\":{\"type\":\"http\",\"url\":\"" + url + "\"}}}";
  }

  /**
   * A PNG with {@code nonce} drawn as large black text on white — legible to the model's vision.
   */
  private static byte[] noncePng(String nonce) throws Exception {
    BufferedImage img = new BufferedImage(760, 200, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 760, 200);
    g.setColor(Color.BLACK);
    g.setFont(new Font("SansSerif", Font.BOLD, 56));
    g.drawString(nonce, 24, 120);
    g.dispose();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ImageIO.write(img, "png", bos);
    return bos.toByteArray();
  }

  // ---- docker plumbing -------------------------------------------------------------------------

  /** The `docker run --rm` prefix + shared-volume/credential flags for a one-shot claude run. */
  private List<String> baseClaudeRun() {
    List<String> argv = new ArrayList<>(List.of("docker", "run", "--rm"));
    argv.addAll(runFlags());
    return argv;
  }

  /** The volume + credential-dir + host-reachability + uid flags shared by both legs. */
  private List<String> runFlags() {
    return new ArrayList<>(
        List.of(
            "--user",
            hostUid(),
            "--add-host=host.docker.internal:host-gateway",
            "-v",
            VOLUME + ":" + MOUNT,
            "-e",
            "CLAUDE_CONFIG_DIR=" + MOUNT + "/.claude",
            "-e",
            "HOME=" + MOUNT,
            "-w",
            "/workspace"));
  }

  private boolean backendAvailable() {
    try {
      if (new ProcessBuilder("docker", "image", "inspect", IMAGE).start().waitFor() != 0) {
        return false;
      }
      List<String> argv = new ArrayList<>(baseClaudeRun());
      argv.addAll(List.of(IMAGE, "claude", "auth", "status"));
      return runCapture(argv, 60).exitCode() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Whether a throwaway container can reach the launched app (curl connects — any status counts).
   */
  private boolean containerCanReachApp() {
    ExecResult r =
        runCapture(
            List.of(
                "docker",
                "run",
                "--rm",
                "--add-host=host.docker.internal:host-gateway",
                IMAGE,
                "curl",
                "-s",
                "-o",
                "/dev/null",
                "-w",
                "%{http_code}",
                "--max-time",
                "8",
                "http://" + GIT_HOST + ":" + PORT + "/api/projects"),
            30);
    return !r.output().trim().equals("000") && !r.output().isBlank();
  }

  private boolean tmuxAvailable() {
    List<String> argv = new ArrayList<>(List.of("docker", "run", "--rm"));
    argv.addAll(List.of(IMAGE, "tmux", "-V"));
    return runCapture(argv, 30).exitCode() == 0;
  }

  private static String hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return String.valueOf(((Number) uid).longValue());
    } catch (Exception e) {
      return "1000";
    }
  }

  private static String shSingleQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private void assertExit0(ExecResult r, String what) {
    assertTrue(r.exitCode() == 0, what + " failed [" + r.exitCode() + "]: " + r.output());
  }

  private ExecResult runCapture(List<String> command, long timeoutSeconds) {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return new ExecResult(-1, "timed out after " + timeoutSeconds + "s: " + output);
      }
      return new ExecResult(p.exitValue(), output);
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }
}
