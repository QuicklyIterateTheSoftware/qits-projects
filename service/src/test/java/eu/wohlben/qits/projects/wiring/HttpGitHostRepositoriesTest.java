package eu.wohlben.qits.projects.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitHostException;
import eu.wohlben.qits.projects.control.GitHostRepositories;
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
 * {@link HttpGitHostRepositories} against a local server standing in for qits-githost — plain
 * JUnit over a directly-constructed bean, {@code DnsDomainRegistrarTest}'s idiom. What is under
 * test is the wire shape: the exact absolute url, method and body {@code ensure}/{@code find} send
 * (§2.3's {@code PUT}/{@code GET /git/<repoId>}), and which status codes become which
 * outcome.
 */
class HttpGitHostRepositoriesTest {

  private record Received(String method, String path, String body) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("");

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
                  new String(requestBytes, StandardCharsets.UTF_8)));
          byte[] responseBytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), responseBytes.length == 0 ? -1 : responseBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** A {@link GitHostAddress} naming exactly the §2.3 route on the stub server, nothing more. */
  private static GitHostAddress addressOf(String base) {
    return new GitHostAddress() {
      @Override
      public String fetchUrl(String repoId) {
        return base + "/git/" + repoId;
      }

      @Override
      public String pushUrl(String repoId) {
        return fetchUrl(repoId);
      }
    };
  }

  private HttpGitHostRepositories repositoriesAgainst(String base) {
    HttpGitHostRepositories repositories = new HttpGitHostRepositories();
    repositories.gitHost = addressOf(base);
    repositories.objectMapper = new ObjectMapper();
    repositories.networkTimeoutMs = 5_000;
    return repositories;
  }

  // --- ensure() -------------------------------------------------------------------------------

  @Test
  void ensureSendsAPutToTheExactArtifactsGitUrlWithTheDefaultBranchBody() throws Exception {
    String base = startServer();
    status.set(201);

    boolean created = repositoriesAgainst(base).ensure("repo-1", "main");

    assertTrue(created, "201 is a fresh create");
    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("PUT", request.method());
    assertEquals("/git/repo-1", request.path());
    assertEquals(Map.of("defaultBranch", "main"), new ObjectMapper().readValue(request.body(), Map.class));
  }

  @Test
  void ensureReports200AsAlreadyExistingRatherThanCreated() throws Exception {
    String base = startServer();
    status.set(200);

    boolean created = repositoriesAgainst(base).ensure("repo-1", "main");

    assertFalse(created, "200 is the idempotent no-op arm");
  }

  @Test
  void ensureThrowsOnAnUnexpectedStatus() throws Exception {
    String base = startServer();
    status.set(400);
    responseBody.set("{\"message\":\"bad branch name\"}");

    GitHostException failure =
        assertThrows(GitHostException.class, () -> repositoriesAgainst(base).ensure("repo-1", "-bad"));
    assertTrue(failure.getMessage().contains("400"), "got: " + failure.getMessage());
  }

  @Test
  void ensureThrowsWhenTheHostIsUnreachable() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    GitHostException failure =
        assertThrows(
            GitHostException.class, () -> repositoriesAgainst("http://127.0.0.1:1").ensure("repo-1", "main"));
    assertTrue(failure.getMessage().contains("unreachable"), "got: " + failure.getMessage());
  }

  // --- find() -----------------------------------------------------------------------------------

  @Test
  void findSendsAGetToTheExactArtifactsGitUrlAndReadsTheDefaultBranch() throws Exception {
    String base = startServer();
    status.set(200);
    responseBody.set("{\"repoId\":\"repo-1\",\"defaultBranch\":\"main\"}");

    Optional<GitHostRepositories.HostRepository> found = repositoriesAgainst(base).find("repo-1");

    assertTrue(found.isPresent());
    assertEquals("main", found.get().defaultBranch());
    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("GET", request.method());
    assertEquals("/git/repo-1", request.path());
  }

  @Test
  void findIsEmptyOn404() throws Exception {
    String base = startServer();
    status.set(404);

    assertTrue(repositoriesAgainst(base).find("no-such-repo").isEmpty());
  }

  @Test
  void findThrowsOnAServerError() throws Exception {
    String base = startServer();
    status.set(500);

    assertThrows(GitHostException.class, () -> repositoriesAgainst(base).find("repo-1"));
  }

  @Test
  void findThrowsWhenTheHostIsUnreachable() {
    GitHostException failure =
        assertThrows(
            GitHostException.class, () -> repositoriesAgainst("http://127.0.0.1:1").find("repo-1"));
    assertTrue(failure.getMessage().contains("unreachable"), "got: " + failure.getMessage());
  }
}
