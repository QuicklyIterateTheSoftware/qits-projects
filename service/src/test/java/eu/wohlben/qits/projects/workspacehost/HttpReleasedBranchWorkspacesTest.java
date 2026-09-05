package eu.wohlben.qits.projects.workspacehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpReleasedBranchWorkspaces} against a local server standing in for qits-workspaces —
 * plain JUnit over a directly-constructed bean, {@code wiring/HttpGitHostRepositoriesTest}'s shape.
 *
 * <p>Two things are under test and they are the two the flow cannot see: the exact wire shape (the
 * absolute url including the {@code repositoryId} query parameter, the method, the bearer and the
 * four body members qits-workspaces' {@code BranchResolutionController} declares), and that
 * <b>every</b> way this can go wrong returns normally. The second is the port's whole contract: the
 * release has already happened when this runs.
 */
class HttpReleasedBranchWorkspacesTest {

  private record Received(String method, String path, String query, String body, String auth) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("{\"resolved\":false}");

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] requestBytes = exchange.getRequestBody().readAllBytes();
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getQuery(),
                  new String(requestBytes, StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] responseBytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(
              status.get(), responseBytes.length == 0 ? -1 : responseBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpReleasedBranchWorkspaces against(String base, Optional<String> authorization) {
    HttpReleasedBranchWorkspaces workspaces = new HttpReleasedBranchWorkspaces();
    workspaces.workspacesUrl = Optional.ofNullable(base);
    workspaces.bearer = () -> authorization;
    return workspaces;
  }

  private HttpReleasedBranchWorkspaces against(String base) {
    return against(base, Optional.of("Bearer machine-token"));
  }

  @Test
  void aReleasedBranchIsPostedToTheResolutionDoorWithTheReleaseOnTheBody() throws Exception {
    String base = startServer();
    responseBody.set("{\"resolved\":true,\"workspaceId\":41}");

    against(base).branchReleased("repo-1", "task/thing", "2026.905.73500", "abc123");

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals("/workspaces/api/branches/resolution", request.path());
    assertEquals("repositoryId=repo-1", request.query());
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(
        Map.of(
            "branch", "task/thing",
            "target", "2026.905.73500",
            "commit", "abc123",
            "result", "released as 2026.905.73500"),
        new ObjectMapper().readValue(request.body(), Map.class));
  }

  @Test
  void noWorkspaceOnTheBranchIsAnOrdinaryAnswerAndNotAFailure() throws Exception {
    String base = startServer();
    responseBody.set("{\"resolved\":false}");

    assertDoesNotThrow(() -> against(base).branchReleased("repo-1", "work", "v", "sha"));
    assertEquals(1, received.size(), "and it was asked exactly once");
  }

  @Test
  void aRefusalIsLoggedAndNeverThrown() throws Exception {
    String base = startServer();
    status.set(400);
    responseBody.set("{\"message\":\"that is the main workspace\"}");

    assertDoesNotThrow(() -> against(base).branchReleased("repo-1", "main", "v", "sha"));
  }

  @Test
  void anAnswerThatWillNotParseIsLoggedAndNeverThrown() throws Exception {
    String base = startServer();
    responseBody.set("not json at all");

    assertDoesNotThrow(() -> against(base).branchReleased("repo-1", "work", "v", "sha"));
  }

  @Test
  void anUnreachableWorkspacesIsLoggedAndNeverThrown() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    assertDoesNotThrow(
        () -> against("http://127.0.0.1:1").branchReleased("repo-1", "work", "v", "sha"));
  }

  @Test
  void anUnsetOrBlankAddressSendsNothing() throws Exception {
    String base = startServer();

    against(null).branchReleased("repo-1", "work", "v", "sha");
    against("   ").branchReleased("repo-1", "work", "v", "sha");

    assertTrue(received.isEmpty(), "a switched-off hop dials nothing: " + received);
    assertTrue(base.startsWith("http://"), "the server was up, so an attempt would have landed");
  }

  /** No credential is not a reason to ask anonymously: the far side destroys a container. */
  @Test
  void noBearerSendsNothing() throws Exception {
    String base = startServer();

    against(base, Optional.empty()).branchReleased("repo-1", "work", "v", "sha");

    assertTrue(received.isEmpty(), "an uncredentialed hop dials nothing: " + received);
  }
}
