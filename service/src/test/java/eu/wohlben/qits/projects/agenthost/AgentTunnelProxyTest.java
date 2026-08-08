package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonCodec;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The whole reverse path, end to end, with this test playing the daemon: control socket → {@code
 * OpenStream} → dial-back → byte pipe → the daemon's own API.
 *
 * <p>Nothing smaller proves what matters here. A stub origin reached directly would show neither of
 * the two defects this design exists around — that {@code vertx-http-proxy} drops its interceptors
 * on an upgrade, and that a tunnel with no backpressure is a heap leak — and, more to the point,
 * would not prove that the request the daemon receives is the request the browser sent. So the
 * fixture is a real loopback HTTP server standing in for {@code ProjectsApi}, reached only the way
 * a real one is reachable: over a connection this test dialled out.
 *
 * <p>Three things are asserted, and each is a rule stated somewhere else in this package:
 *
 * <ul>
 *   <li>the bearer is <b>set</b> by the proxy, never carried by the caller;
 *   <li>the path is forwarded <b>verbatim</b>, prefix included, because the daemon was told that
 *       prefix is its own address;
 *   <li>a nonce is <b>single-use</b> — the second dial-back with the same one is refused.
 * </ul>
 */
@QuarkusTest
class AgentTunnelProxyTest {

  private static final long TIMEOUT_SECONDS = 15;

  /**
   * Every request here is HTTP/1.1, and that is a finding rather than a preference.
   *
   * <p>{@code java.net.http} negotiates HTTP/2 by default and Quarkus serves cleartext h2. Over
   * that, {@code vertx-http-proxy} pipes the inbound body into an HTTP/1.1 client request that has
   * neither {@code Content-Length} nor chunked framing set, and the END_STREAM data frame of a
   * <em>bodyless</em> GET is enough to throw — the response still lands, so the only symptom is one
   * uncaught {@code IllegalStateException} on the event loop. It is a property of the library,
   * shared with qits-workspaces' identical route, and it is not this repository's to fix from here.
   * The real caller is a browser through qits-gateway, which speaks HTTP/1.1 to this service, so
   * pinning the version is what makes the fixture the deployment rather than what hides the defect.
   */
  private static final HttpClient.Version PROXY_HOP_VERSION = HttpClient.Version.HTTP_1_1;

  @Inject Vertx vertx;

  @Inject AgentTunnels tunnels;

  @TestHTTPResource("/")
  URL root;

  /** The project this test plays the daemon for; null until one connects. */
  private String projectId;

  private final ObjectMapper json = new ObjectMapper();

  private HttpServer daemonApi;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** What the stand-in {@code ProjectsApi} saw on the request that reached it. */
  private record Seen(String method, String uri, String authorization, String host) {}

  private final CompletableFuture<Seen> seen = new CompletableFuture<>();

  /** The nonce the host minted, captured off the {@code OpenStream} so it can be replayed. */
  private final CompletableFuture<String> mintedNonce = new CompletableFuture<>();

  @AfterEach
  void tearDown() {
    // The tunnel first, and its own client with it. Pull the daemon's sockets out from under a
    // pooled connection instead and the proxy sees an idle upstream die mid-pool, which is what a
    // stopped container looks like — real, harmless, and one Vert.x ERROR line of noise per test.
    // AgentContainers.stop closes the tunnel for exactly this reason.
    if (projectId != null) {
      tunnels.closeTunnel(projectId);
    }
    if (controlSocket != null) {
      controlSocket.close();
    }
    if (wsClient != null) {
      wsClient.close();
    }
    if (netClient != null) {
      netClient.close();
    }
    if (daemonApi != null) {
      daemonApi.close();
    }
  }

  /** A loopback HTTP server standing in for the daemon's own API, recording what reached it. */
  private int startDaemonApi() throws Exception {
    daemonApi =
        vertx
            .createHttpServer()
            .requestHandler(
                request -> {
                  seen.complete(
                      new Seen(
                          request.method().name(),
                          request.uri(),
                          request.getHeader("Authorization"),
                          request.getHeader("Host")));
                  request
                      .response()
                      .putHeader("Content-Type", "application/json")
                      .end("{\"ok\":true}");
                })
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return daemonApi.actualPort();
  }

  /**
   * Dial the control socket, say {@code Hello}, and serve every {@code OpenStream} the way the real
   * daemon does: connect to loopback first, dial back second — reversed, the host can start writing
   * before this side has anywhere to put the bytes, and losing the request line presents as a
   * request that is simply never answered.
   */
  private void connectAsDaemon(String id, int apiPort) throws Exception {
    this.projectId = id;
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    CompletableFuture<Void> acked = new CompletableFuture<>();

    controlSocket =
        wsClient
            .connect(
                new WebSocketConnectOptions()
                    .setHost(root.getHost())
                    .setPort(root.getPort())
                    .setURI(DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + id))
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    controlSocket.textMessageHandler(
        frame -> {
          JsonObject decoded = new JsonObject(frame);
          String type = decoded.getString(DaemonProtocol.Field.TYPE);
          if (DaemonProtocol.Type.ACK.equals(type)) {
            acked.complete(null);
          } else if (DaemonProtocol.Type.OPEN_STREAM.equals(type)) {
            String nonce = decoded.getString(DaemonProtocol.Field.NONCE);
            mintedNonce.complete(nonce);
            serveStream(decoded.getString(DaemonProtocol.Field.PATH), apiPort);
          }
        });

    controlSocket.writeTextMessage(
        encode(new Hello(id, "demo-demo", DaemonProtocol.CAPABILITY_VERSION, "test", null)));
    acked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private String encode(Hello hello) {
    try {
      return json.writeValueAsString(DaemonCodec.encode(hello));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** One stream: loopback connection first, dial-back second, then a raw byte pipe both ways. */
  private void serveStream(String path, int apiPort) {
    netClient
        .connect(apiPort, "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(
                        new WebSocketConnectOptions()
                            .setHost(root.getHost())
                            .setPort(root.getPort())
                            .setURI(path))
                    .onSuccess(
                        remote -> {
                          remote.handler(local::write);
                          local.handler(remote::writeBinaryMessage);
                          remote.endHandler(v -> local.close());
                          local.endHandler(v -> remote.close());
                        })
                    .onFailure(t -> local.close()));
  }

  @Test
  void forwardsThroughTheTunnelWithTheBearerSetAndThePathUntouched() throws Exception {
    String project = UUID.randomUUID().toString();
    int apiPort = startDaemonApi();
    connectAsDaemon(project, apiPort);

    String path = ContainerProxyPath.base(project) + "commands";
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://" + root.getHost() + ":" + root.getPort() + path))
                    .version(PROXY_HOP_VERSION)
                    // A caller-supplied credential the proxy must REPLACE rather than forward:
                    // the daemon's bearer says "qits is calling", not "this user is calling".
                    .header("Authorization", "Bearer not-the-daemons-token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertEquals("{\"ok\":true}", response.body());

    Seen request = seen.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals("GET", request.method());
    assertEquals(
        path,
        request.uri(),
        "verbatim: the daemon is told this prefix is its own address, so nothing rewrites it");
    assertEquals(
        "Bearer qits-projects-daemon",
        request.authorization(),
        "set by the proxy, replacing whatever the caller sent");
    assertEquals(
        "localhost:13338",
        request.host(),
        "the authority is pinned, so it does not move with the tunnel's ephemeral port");
  }

  @Test
  void aNonceIsSingleUse() throws Exception {
    String project = UUID.randomUUID().toString();
    int apiPort = startDaemonApi();
    connectAsDaemon(project, apiPort);

    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + root.getHost()
                            + ":"
                            + root.getPort()
                            + ContainerProxyPath.base(project)
                            + "commands"))
                .version(PROXY_HOP_VERSION)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    String nonce = mintedNonce.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(nonce);

    // The claim is an atomic map removal, so a replay finds nothing. Refused at the handshake,
    // with a bare 404 that says nothing about whether the nonce was unknown or already used.
    ExecutionException refused =
        assertThrows(
            ExecutionException.class,
            () ->
                wsClient
                    .connect(
                        new WebSocketConnectOptions()
                            .setHost(root.getHost())
                            .setPort(root.getPort())
                            .setURI(AgentTunnels.STREAM_PATH_PREFIX + nonce))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue(String.valueOf(refused.getCause()).contains("404"));
  }

  @Test
  void aProjectWithNoDaemonIsUnreachableRatherThanDialledSomewhere() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://"
                                + root.getHost()
                                + ":"
                                + root.getPort()
                                + ContainerProxyPath.base(UUID.randomUUID().toString())
                                + "commands"))
                    .version(PROXY_HOP_VERSION)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    // There is no direct branch to fall back to: the daemon binds loopback and has no address on
    // the shared network at all, so "no live control socket" is the whole answer.
    assertEquals(502, response.statusCode());
    assertTrue(response.body().contains("not running"));
  }

  @Test
  void anUnknownNonceIs404() throws Exception {
    wsClient = vertx.createWebSocketClient();
    assertThrows(
        ExecutionException.class,
        () ->
            wsClient
                .connect(
                    new WebSocketConnectOptions()
                        .setHost(root.getHost())
                        .setPort(root.getPort())
                        .setURI(AgentTunnels.STREAM_PATH_PREFIX + "not-a-nonce"))
                .toCompletionStage()
                .toCompletableFuture()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }
}
