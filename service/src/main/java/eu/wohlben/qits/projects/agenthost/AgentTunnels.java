package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host end of the reverse tunnel: a loopback {@link NetServer} per project whose accepted TCP
 * connections are handed to that project's daemon, which dials back and pipes them to its own
 * {@code ProjectsApi}. A verbatim adaptation of qits-workspaces' {@code WorkspaceTunnels}; its
 * reasoning is reproduced here because every line of it still applies.
 *
 * <h2>Why a loopback listener, of all things</h2>
 *
 * <p>Because the host has to speak HTTP over a connection it did not initiate, and Vert.x has no
 * API for "an {@code HttpClient} over a socket I supply" — {@code HttpProxy} offers {@code
 * origin(…)}, {@code originSelector(…)} and {@code originRequestProvider(…)}, all of which want a
 * real address. A loopback {@code NetServer} <em>is</em> a real address, so the proxy stays an
 * ordinary reverse proxy and the only thing that changes is which host:port it points at.
 *
 * <p>It also means the tunnel carries bytes rather than framed requests, which is what lets a
 * WebSocket upgrade traverse it unchanged. {@code vertx-http-proxy} already turns an upgraded
 * exchange into a raw byte pipe, so the two compose instead of fighting.
 *
 * <h2>One HttpClient per project, and why it is not an optimisation to share</h2>
 *
 * <p>An ephemeral port is reused. Project A's tunnel closes, the OS later hands the same port to
 * project B's, and a pool keyed on {@code (host, port)} may still hold a live connection wired
 * through to <em>A's</em> daemon — which it would then hand to a request for B. That is a
 * cross-project read of someone else's working tree, arrived at without anything being
 * misconfigured. So each tunnel owns its client, created and closed with it, and every accepted
 * socket is closed explicitly at teardown ({@code NetServer.close()} closes the listening channel
 * only; accepted sockets survive it).
 */
@ApplicationScoped
public class AgentTunnels {

  private static final Logger LOG = Logger.getLogger(AgentTunnels.class);

  /**
   * The dial-back path, and a cross-repo contract with the daemon's {@code DaemonStreamTunnel}.
   * Built on the control socket's own prefix so the two cannot drift: {@code {projectId}} matches
   * exactly one segment, so no project can be named {@code stream} and the two never collide.
   */
  static final String STREAM_PATH_PREFIX = DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + "stream/";

  /**
   * An INSTANCE field, and it must stay one. A {@code static final SecureRandom} is initialized by
   * the class initializer, which native-image runs during the build — so the seeded instance lands
   * in the image heap and the build aborts outright with "Detected an instance of Random/…
   * class in the image heap". A CDI bean is constructed at runtime, so as a field of the bean it
   * never reaches the heap the builder writes. That is also why {@link #mintNonce} is not static.
   * GraalVM refusing this case is the one mercy here — a nonce generator with a build-time seed
   * would be a credential identical in every deployment of the same image.
   */
  private final SecureRandom random = new SecureRandom();

  @Inject Vertx vertx;

  @Inject AgentDaemonRegistry registry;

  /**
   * The kill switch. A tunnel that misbehaves can be turned off without rolling back an image —
   * with the caveat that the daemon binds loopback and has no address on {@code qits-net} at all,
   * so switching this off makes its API unreachable rather than reachable some older way. It is an
   * escape hatch for a broken tunnel, not a supported topology.
   */
  @ConfigProperty(name = "qits.projects.agent-tunnel.enabled", defaultValue = "true")
  boolean enabled;

  /**
   * How long a minted nonce stays claimable. Generous for a docker network and short for a bearer
   * credential; the only thing that happens in the window is one WebSocket dial.
   */
  @ConfigProperty(name = "qits.projects.agent-tunnel.nonce-ttl-ms", defaultValue = "10000")
  long nonceTtlMs;

  private final ConcurrentHashMap<String, Tunnel> tunnels = new ConcurrentHashMap<>();

  /** Minted-but-unclaimed nonces, across every project. Single-use by construction. */
  private final ConcurrentHashMap<String, Parked> pending = new ConcurrentHashMap<>();

  /** One project's tunnel: its listener, its client, and the sockets it has accepted. */
  private static final class Tunnel {
    private final NetServer server;
    private final HttpClient client;

    /**
     * The daemon connection this tunnel belongs to. A reconnect mints a new {@code connectedAt}, and
     * a tunnel whose daemon has been replaced must be rebuilt rather than reused — its parked
     * sockets would be waiting on a socket that is gone.
     */
    private final Instant connectedAt;

    private final Set<NetSocket> accepted = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Tunnel(NetServer server, HttpClient client, Instant connectedAt) {
      this.server = server;
      this.client = client;
      this.connectedAt = connectedAt;
    }

    void close() {
      // Explicitly, and before the server: NetServer.close() closes only the listening channel, so
      // an accepted socket would otherwise outlive its tunnel and keep a pooled connection alive
      // against a port the OS is free to hand to another project.
      accepted.forEach(NetSocket::close);
      accepted.clear();
      client.close();
      server.close();
    }
  }

  /**
   * A TCP connection waiting for its daemon to dial back, and whatever the proxy has already
   * written to it.
   *
   * <p>The buffer is not an optimisation — it is the fix for a race that presents as the request
   * simply never being answered. The proxy writes its request bytes as soon as it connects, which
   * can be before the daemon has dialled back and before any handler exists to receive them.
   * Pausing the socket is not enough on its own, so an interim handler collects whatever arrives
   * and {@code DaemonStreamRoute} replays it before wiring the two ends together.
   */
  record Parked(String projectId, NetSocket socket, long timerId, Buffer early) {}

  /** One project's tunnel entrance: the loopback port, and the client that must be used for it. */
  public record TunnelOrigin(HttpClient client, int port) {}

  /**
   * Where to reach {@code projectId}'s daemon through the tunnel, or empty when that daemon cannot
   * be reached this way — no daemon connected, or one too old to serve a stream.
   *
   * <p>The caller gets the client as well as the port, and <b>must</b> use that client: it belongs
   * to this project's tunnel and is closed with it, which is what keeps a reused ephemeral port
   * from handing one project a pooled connection into another's container. It is not an
   * optimisation to reach for a shared one.
   *
   * <p>A live control socket is what proves the container is up here, which is why this asks the
   * registry rather than docker: it is both stronger evidence and one less round-trip per request.
   *
   * <p>Blocking (it awaits a bind on first use), so call it off the event loop.
   */
  public Optional<TunnelOrigin> originFor(String projectId) {
    if (!enabled || projectId == null || projectId.isBlank()) {
      return Optional.empty();
    }
    AgentDaemonRegistry.DaemonInfo info = registry.lookup(projectId).orElse(null);
    if (info == null || info.capabilityVersion() < DaemonProtocol.CAPABILITY_VERSION) {
      // No daemon, or one that has not said hello yet. Capability 1 already binds loopback and
      // serves OpenStream, so there is no older shape to fall back to: this project is simply not
      // reachable, and the proxy says so rather than inventing an address.
      closeTunnel(projectId);
      return Optional.empty();
    }
    Tunnel existing = tunnels.get(projectId);
    if (existing != null && existing.connectedAt.equals(info.connectedAt())) {
      return Optional.of(originOf(existing));
    }
    closeTunnel(projectId);
    try {
      return Optional.of(originOf(openTunnel(projectId, info.connectedAt())));
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not open an agent tunnel for project %s", projectId);
      return Optional.empty();
    }
  }

  private static TunnelOrigin originOf(Tunnel tunnel) {
    return new TunnelOrigin(tunnel.client, tunnel.server.actualPort());
  }

  private Tunnel openTunnel(String projectId, Instant connectedAt) {
    // 127.0.0.1 is a literal and not a config key on purpose: a configurable bind address here
    // would be an SSRF footgun with no caller asking for it.
    NetServer server = vertx.createNetServer();
    server.connectHandler(socket -> onAccepted(projectId, socket));
    NetServer bound = await(server.listen(0, "127.0.0.1"));
    HttpClient client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true));
    Tunnel tunnel = new Tunnel(bound, client, connectedAt);
    tunnels.put(projectId, tunnel);
    LOG.debugf(
        "agent tunnel for project %s listening on 127.0.0.1:%s",
        projectId, Integer.valueOf(bound.actualPort()));
    return tunnel;
  }

  /**
   * One accepted connection: park it, ask its daemon to come and get it.
   *
   * <p>Runs on an event loop, so the {@code OpenStream} is sent without awaiting.
   *
   * <p>The nonce is registered <em>before</em> the message goes out. That ordering is the only one
   * that works: a dial-back can arrive before the send's own callback does.
   */
  private void onAccepted(String projectId, NetSocket socket) {
    Tunnel tunnel = tunnels.get(projectId);
    if (tunnel == null) {
      socket.close();
      return;
    }
    tunnel.accepted.add(socket);
    socket.closeHandler(v -> tunnel.accepted.remove(socket));
    // Collect whatever the proxy writes before the far end exists; DaemonStreamRoute replays it.
    Buffer early = Buffer.buffer();
    socket.handler(early::appendBuffer);

    String nonce = mintNonce();
    long timerId =
        vertx.setTimer(
            nonceTtlMs,
            id -> {
              Parked expired = pending.remove(nonce);
              if (expired != null) {
                // The daemon never came. Closing is what turns this into a connection error at the
                // proxy rather than a request that hangs until some other timeout notices.
                LOG.debugf("agent tunnel stream for project %s expired unclaimed", projectId);
                expired.socket().close();
              }
            });
    pending.put(nonce, new Parked(projectId, socket, timerId, early));
    registry.requestStream(projectId, nonce, STREAM_PATH_PREFIX + nonce);
  }

  /**
   * Claim a nonce, once. The atomic {@code remove} is what makes single-use structural rather than
   * a rule someone has to remember — a replayed nonce finds nothing.
   */
  Optional<Parked> claim(String nonce) {
    Parked parked = nonce == null ? null : pending.remove(nonce);
    if (parked != null) {
      vertx.cancelTimer(parked.timerId());
    }
    return Optional.ofNullable(parked);
  }

  /**
   * A daemon's control socket went away: drop its <em>pending</em> nonces and nothing else.
   *
   * <p>Live tunnels deliberately survive. Each stream is an independent TCP connection, so a
   * control socket bouncing through a reconnect leaves an open terminal open — which is the whole
   * reason these calls do not ride the control socket in the first place, and would be quietly
   * undone by tearing tunnels down here.
   */
  void onDaemonGone(String projectId) {
    pending.forEach(
        (nonce, parked) -> {
          if (parked.projectId().equals(projectId) && pending.remove(nonce, parked)) {
            vertx.cancelTimer(parked.timerId());
            parked.socket().close();
          }
        });
  }

  /** Tear a project's tunnel down — on a daemon replacement, and when its container is stopped. */
  public void closeTunnel(String projectId) {
    Tunnel gone = tunnels.remove(projectId);
    if (gone != null) {
      gone.close();
    }
  }

  /**
   * 32 bytes of {@link SecureRandom}, base64url. Not a {@code UUID}: this is a bearer credential,
   * and this codebase spells correlation ids as UUIDs — using one here would make the wrong thing
   * look right to the next reader.
   *
   * <p>Not static, and not by accident — see {@link #random}.
   */
  private String mintNonce() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static <T> T await(io.vertx.core.Future<T> future) {
    try {
      return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted opening an agent tunnel", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not open an agent tunnel", e);
    }
  }

  @PreDestroy
  void closeAll() {
    tunnels.keySet().forEach(this::closeTunnel);
    pending.values().forEach(parked -> parked.socket().close());
    pending.clear();
  }
}
