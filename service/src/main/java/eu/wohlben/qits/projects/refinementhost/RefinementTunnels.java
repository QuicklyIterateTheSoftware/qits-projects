package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
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
 * The host end of the refinement reverse tunnel: a loopback {@link NetServer} per refinement whose
 * accepted TCP connections are handed to that refinement's workspace daemon, which dials back and
 * pipes them to its own loopback API. The refinement twin of {@code agenthost/AgentTunnels}; the
 * loopback-listener and one-client-per-tunnel reasoning lives there and in qits-workspaces'
 * {@code WorkspaceTunnels} and applies here unchanged.
 *
 * <p>The one difference of substance: the capability gate is the workspace protocol's
 * {@link DaemonProtocol#TUNNEL_CAPABILITY_VERSION}. A workspace daemon below it binds
 * {@code 0.0.0.0} and cannot answer an {@code OpenStream} at all — but every image this factory
 * pins is far past that, so "too old" collapses into "not reachable".
 */
@ApplicationScoped
public class RefinementTunnels {

  private static final Logger LOG = Logger.getLogger(RefinementTunnels.class);

  /**
   * An INSTANCE field, and it must stay one — a {@code static final SecureRandom} lands in the
   * native-image build heap and aborts the build. See {@code AgentTunnels#random}, which carries
   * the full argument; {@link #mintNonce} is non-static for the same reason.
   */
  private final SecureRandom random = new SecureRandom();

  @Inject Vertx vertx;

  @Inject RefinementDaemonRegistry registry;

  @ConfigProperty(name = "qits.projects.refinement-tunnel.enabled", defaultValue = "true")
  boolean enabled;

  @ConfigProperty(name = "qits.projects.refinement-tunnel.nonce-ttl-ms", defaultValue = "10000")
  long nonceTtlMs;

  private final ConcurrentHashMap<Long, Tunnel> tunnels = new ConcurrentHashMap<>();

  /** Minted-but-unclaimed nonces, across every refinement. Single-use by construction. */
  private final ConcurrentHashMap<String, Parked> pending = new ConcurrentHashMap<>();

  private static final class Tunnel {
    private final NetServer server;
    private final HttpClient client;
    private final Instant connectedAt;
    private final Set<NetSocket> accepted = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Tunnel(NetServer server, HttpClient client, Instant connectedAt) {
      this.server = server;
      this.client = client;
      this.connectedAt = connectedAt;
    }

    void close() {
      accepted.forEach(NetSocket::close);
      accepted.clear();
      client.close();
      server.close();
    }
  }

  /** A TCP connection waiting for its daemon to dial back, plus the proxy's early bytes. */
  record Parked(Long refinementId, NetSocket socket, long timerId, Buffer early) {}

  /** One refinement's tunnel entrance: the loopback port, and the client that must be used. */
  public record TunnelOrigin(HttpClient client, int port) {}

  /**
   * Where to reach {@code refinementId}'s daemon through the tunnel, or empty when it cannot be
   * reached this way. Blocking (awaits a bind on first use) — call it off the event loop.
   */
  public Optional<TunnelOrigin> originFor(Long refinementId) {
    if (!enabled || refinementId == null) {
      return Optional.empty();
    }
    RefinementDaemonRegistry.DaemonInfo info = registry.lookup(refinementId).orElse(null);
    if (info == null || info.capabilityVersion() < DaemonProtocol.TUNNEL_CAPABILITY_VERSION) {
      closeTunnel(refinementId);
      return Optional.empty();
    }
    Tunnel existing = tunnels.get(refinementId);
    if (existing != null && existing.connectedAt.equals(info.connectedAt())) {
      return Optional.of(originOf(existing));
    }
    closeTunnel(refinementId);
    try {
      return Optional.of(originOf(openTunnel(refinementId, info.connectedAt())));
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not open a refinement tunnel for %s", refinementId);
      return Optional.empty();
    }
  }

  private static TunnelOrigin originOf(Tunnel tunnel) {
    return new TunnelOrigin(tunnel.client, tunnel.server.actualPort());
  }

  private Tunnel openTunnel(Long refinementId, Instant connectedAt) {
    // 127.0.0.1 is a literal, not a config key: a configurable bind address here would be an SSRF
    // footgun with no caller asking for it.
    NetServer server = vertx.createNetServer();
    server.connectHandler(socket -> onAccepted(refinementId, socket));
    NetServer bound = await(server.listen(0, "127.0.0.1"));
    HttpClient client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true));
    Tunnel tunnel = new Tunnel(bound, client, connectedAt);
    tunnels.put(refinementId, tunnel);
    return tunnel;
  }

  /** Park an accepted connection and ask its daemon to come and get it. Event-loop safe. */
  private void onAccepted(Long refinementId, NetSocket socket) {
    Tunnel tunnel = tunnels.get(refinementId);
    if (tunnel == null) {
      socket.close();
      return;
    }
    tunnel.accepted.add(socket);
    socket.closeHandler(v -> tunnel.accepted.remove(socket));
    Buffer early = Buffer.buffer();
    socket.handler(early::appendBuffer);

    String nonce = mintNonce();
    long timerId =
        vertx.setTimer(
            nonceTtlMs,
            id -> {
              Parked expired = pending.remove(nonce);
              if (expired != null) {
                LOG.debugf("refinement tunnel stream for %s expired unclaimed", refinementId);
                expired.socket().close();
              }
            });
    // Registered BEFORE the message goes out: a dial-back can beat the send's own callback.
    pending.put(nonce, new Parked(refinementId, socket, timerId, early));
    registry.requestStream(refinementId, nonce, RefinementPaths.STREAM_PREFIX + nonce);
  }

  /** Claim a nonce, once — the atomic remove is what makes single-use structural. */
  Optional<Parked> claim(String nonce) {
    Parked parked = nonce == null ? null : pending.remove(nonce);
    if (parked != null) {
      vertx.cancelTimer(parked.timerId());
    }
    return Optional.ofNullable(parked);
  }

  /** A daemon's control socket went away: drop its pending nonces; live tunnels survive. */
  void onDaemonGone(Long refinementId) {
    pending.forEach(
        (nonce, parked) -> {
          if (parked.refinementId().equals(refinementId) && pending.remove(nonce, parked)) {
            vertx.cancelTimer(parked.timerId());
            parked.socket().close();
          }
        });
  }

  /** Tear a refinement's tunnel down — on daemon replacement and container stop/discard. */
  public void closeTunnel(Long refinementId) {
    Tunnel gone = tunnels.remove(refinementId);
    if (gone != null) {
      gone.close();
    }
  }

  /** 32 bytes of {@link SecureRandom}, base64url — a bearer credential, not a correlation id. */
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
      throw new IllegalStateException("interrupted opening a refinement tunnel", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not open a refinement tunnel", e);
    }
  }

  @PreDestroy
  void closeAll() {
    tunnels.keySet().forEach(this::closeTunnel);
    pending.values().forEach(parked -> parked.socket().close());
    pending.clear();
  }
}
