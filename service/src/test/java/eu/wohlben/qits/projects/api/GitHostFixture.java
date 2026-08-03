package eu.wohlben.qits.projects.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Stands in for qits-artifacts' git host, for {@link PackagedSurfaceIT} only.
 *
 * <p>The packaged binary wires {@code ConfiguredGitHostAddress} + {@code HttpGitHostRepositories} —
 * the shipped, production beans, with no test double compiled in — so the CDI fakes {@code
 * FakeGitHostAddress}/{@code FakeGitHostRepositories} (domain test scope, which win inside a {@code
 * @QuarkusTest}'s own JVM) cannot reach a process a {@code @QuarkusIntegrationTest} launches
 * separately. This is that packaged run's counterpart: a real HTTP server in <b>this</b> JVM, handed
 * to the launched process as {@code qits.artifacts.url} (a {@code QuarkusTestResourceLifecycleManager}
 * return value becomes a {@code -D} argument on the launched process for both the fast-jar and the
 * native binary — see {@code ArtifactLauncher.includeAsSysProps}) — so the binary talks to something
 * real instead of an unresolved host name.
 *
 * <p>Mirrors qits-ci's {@code service/src/test/java/.../daemonhost/GitHttpBackend}: shells {@code git
 * http-backend} as CGI for the three smart-HTTP routes, because {@code RepoMirror}'s push needs a
 * real {@code receive-pack} — a static-file handler only speaks the read-only dumb protocol and
 * refuses a push outright ({@code fatal: dumb http transport does not support...}). That is what
 * qits-artifacts' own {@code GitHostRoutes} does behind {@code /artifacts/git/<repoId>}, so shelling
 * the same CGI program here reproduces the wire protocol rather than a fresh reading of it.
 *
 * <p>Bare repositories live under a fixed root as {@code <repoId>.git}, created by the lifecycle
 * {@code PUT} exactly as {@code GitHostRoutes#createRepository} does: {@code git init --bare -b
 * <defaultBranch>}, plus {@code http.receivepack} (off by default — a push over dumb-looking HTTP
 * needs it explicitly) and {@code receive.advertisePushOptions} (JGit advertises it in production;
 * a local {@code receive-pack} does not by default) so a real push — including one carrying {@code -o
 * qits.no-ci}, the imported-history path — is accepted the way it is in production.
 */
public class GitHostFixture implements QuarkusTestResourceLifecycleManager {

  private static final Pattern REPO_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");
  private static final String BASE = "/artifacts/git/";

  private final ObjectMapper mapper = new ObjectMapper();

  private HttpServer server;
  private ExecutorService executor;
  private Path root;

  @Override
  public Map<String, String> start() {
    try {
      // Absolute: passed both as a git argv path and as a ProcessBuilder working directory, and a
      // relative one resolves twice against those two different bases — root/root/<repoId>.git.
      root = Path.of("target", "it-git-host-fixture").toAbsolutePath();
      deleteRecursively(root);
      Files.createDirectories(root);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(BASE, this::handle);
      executor = Executors.newCachedThreadPool();
      server.setExecutor(executor);
      server.start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Map.of("qits.artifacts.url", "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
    deleteRecursively(root);
  }

  // --- routing --------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) {
    try {
      String path = exchange.getRequestURI().getPath();
      String rest = path.substring(BASE.length());
      int slash = rest.indexOf('/');
      String repoId = slash < 0 ? rest : rest.substring(0, slash);
      String suffix = slash < 0 ? "" : rest.substring(slash);
      if (!REPO_ID.matcher(repoId).matches()) {
        respond(exchange, 400, null, new byte[0]);
        return;
      }
      if (suffix.isEmpty()) {
        lifecycle(exchange, repoId);
      } else {
        smartHttp(exchange, repoId, suffix);
      }
    } catch (Exception e) {
      try {
        respond(exchange, 500, null, e.toString().getBytes(StandardCharsets.UTF_8));
      } catch (IOException ignored) {
        // the client is gone; nothing left to report to
      }
    }
  }

  // --- the lifecycle verbs: PUT/GET /artifacts/git/<repoId> -----------------------------------

  /** {@code PUT}/{@code GET …/:repoId} — the same contract {@code HttpGitHostRepositories} speaks. */
  private void lifecycle(HttpExchange exchange, String repoId) throws IOException {
    Path bare = root.resolve(repoId + ".git");
    String method = exchange.getRequestMethod();
    if ("PUT".equals(method)) {
      byte[] body = exchange.getRequestBody().readAllBytes();
      String defaultBranch = readDefaultBranch(body);
      if (defaultBranch == null) {
        respond(exchange, 400, null, new byte[0]);
        return;
      }
      boolean existed = Files.isDirectory(bare);
      if (!existed) {
        run(root.toFile(), "git", "init", "--bare", "-q", "-b", defaultBranch, bare.toString());
        run(bare.toFile(), "git", "config", "http.receivepack", "true");
        run(bare.toFile(), "git", "config", "receive.advertisePushOptions", "true");
      }
      respondRepository(exchange, existed ? 200 : 201, repoId, bare);
      return;
    }
    if ("GET".equals(method)) {
      if (!Files.isDirectory(bare)) {
        respond(exchange, 404, null, new byte[0]);
        return;
      }
      respondRepository(exchange, 200, repoId, bare);
      return;
    }
    respond(exchange, 405, null, new byte[0]);
  }

  private void respondRepository(HttpExchange exchange, int status, String repoId, Path bare)
      throws IOException {
    String json =
        mapper
            .createObjectNode()
            .put("repoId", repoId)
            .put("defaultBranch", defaultBranchOf(bare))
            .toString();
    respond(exchange, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  private String defaultBranchOf(Path bare) {
    return run(bare.toFile(), "git", "symbolic-ref", "--short", "HEAD").trim();
  }

  private String readDefaultBranch(byte[] body) {
    if (body.length == 0) {
      return null;
    }
    try {
      JsonNode node = mapper.readTree(body);
      String candidate = node.path("defaultBranch").asText(null);
      return isValidBranchName(candidate) ? candidate : null;
    } catch (IOException e) {
      return null;
    }
  }

  /** Same argv-safety discipline as qits-artifacts' own {@code isValidBranchName}. */
  private static boolean isValidBranchName(String name) {
    return name != null
        && !name.isBlank()
        && !name.startsWith("-")
        && !name.contains("..")
        && name.chars().noneMatch(Character::isWhitespace);
  }

  private void respond(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    if (contentType != null) {
      exchange.getResponseHeaders().set("Content-Type", contentType);
    }
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      exchange.getResponseBody().write(body);
    }
    exchange.getResponseBody().close();
  }

  // --- smart HTTP: info/refs, git-upload-pack, git-receive-pack ------------------------------

  private void smartHttp(HttpExchange exchange, String repoId, String suffix) throws Exception {
    Path bare = root.resolve(repoId + ".git");
    if (!Files.isDirectory(bare)) {
      respond(exchange, 404, null, new byte[0]);
      return;
    }
    String method = exchange.getRequestMethod();
    String query = exchange.getRequestURI().getRawQuery();
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    String contentEncoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
    byte[] body = exchange.getRequestBody().readAllBytes();
    CgiResponse cgi =
        gitHttpBackend(method, "/" + repoId + ".git" + suffix, query, contentType, contentEncoding, body);
    cgi.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    exchange.sendResponseHeaders(cgi.status(), cgi.body().length == 0 ? -1 : cgi.body().length);
    if (cgi.body().length > 0) {
      exchange.getResponseBody().write(cgi.body());
    }
    exchange.getResponseBody().close();
  }

  /** A CGI program's answer: the {@code Status:} line, the headers it set, and the body. */
  private record CgiResponse(int status, Map<String, String> headers, byte[] body) {}

  /**
   * Run {@code git http-backend} with the CGI environment it expects and split its response. stdin
   * is written on its own thread while stdout is read on this one — a CGI program that starts
   * answering before it has consumed its input would otherwise deadlock a write-then-read
   * implementation.
   */
  private CgiResponse gitHttpBackend(
      String method, String pathInfo, String query, String contentType, String contentEncoding, byte[] body)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("git", "http-backend");
    Map<String, String> env = pb.environment();
    env.put("GIT_PROJECT_ROOT", root.toAbsolutePath().toString());
    env.put("GIT_HTTP_EXPORT_ALL", "1");
    env.put("REQUEST_METHOD", method);
    env.put("PATH_INFO", pathInfo);
    env.put("QUERY_STRING", query == null ? "" : query);
    env.put("REMOTE_ADDR", "127.0.0.1");
    env.put("CONTENT_LENGTH", String.valueOf(body.length));
    if (contentType != null) {
      env.put("CONTENT_TYPE", contentType);
    }
    if (contentEncoding != null) {
      // git may gzip an upload-pack request; http-backend inflates it when told.
      env.put("HTTP_CONTENT_ENCODING", contentEncoding);
    }

    Process process = pb.start();
    Thread stdin =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var out = process.getOutputStream()) {
                    out.write(body);
                  } catch (IOException ignored) {
                    // the child closed its input; whatever it already read is what it answers on
                  }
                });
    Thread stderr =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var err = process.getErrorStream()) {
                    err.readAllBytes(); // drained so a chatty failure cannot wedge us
                  } catch (IOException ignored) {
                    // nothing to report beyond the exit code
                  }
                });
    byte[] raw = process.getInputStream().readAllBytes();
    process.waitFor();
    stdin.join(Duration.ofSeconds(5));
    stderr.join(Duration.ofSeconds(5));
    return parseCgi(raw);
  }

  /** Split a CGI response into headers and body at the first blank line, honouring {@code Status}. */
  private static CgiResponse parseCgi(byte[] raw) {
    int split = -1;
    int bodyAt = -1;
    for (int i = 0; i + 1 < raw.length; i++) {
      if (raw[i] == '\n' && raw[i + 1] == '\n') {
        split = i;
        bodyAt = i + 2;
        break;
      }
      if (i + 3 < raw.length && raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
        split = i;
        bodyAt = i + 4;
        break;
      }
    }
    if (split < 0) {
      return new CgiResponse(500, Map.of(), raw);
    }
    int status = 200;
    Map<String, String> headers = new LinkedHashMap<>();
    String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
    for (String line : head.split("\\R")) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String name = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (name.equalsIgnoreCase("Status")) {
        status = Integer.parseInt(value.split("\\s+")[0]);
      } else if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding")) {
        // Both are ours to decide: the body is written whole, so the HttpServer sets the framing.
        headers.put(name, value);
      }
    }
    return new CgiResponse(status, headers, java.util.Arrays.copyOfRange(raw, bodyAt, raw.length));
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private static String run(File cwd, String... argv) {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
    try {
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException("git " + String.join(" ", argv) + " failed:\n" + output);
      }
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted running git " + String.join(" ", argv), e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // best effort — a leftover entry only costs the next run a stale directory
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
