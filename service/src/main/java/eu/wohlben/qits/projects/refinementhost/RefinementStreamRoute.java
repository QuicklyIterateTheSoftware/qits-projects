package eu.wohlben.qits.projects.refinementhost;

import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Where a refinement daemon's dial-back lands: {@code /projects/refinement-daemon/stream/{nonce}}.
 * The nonce names a TCP connection {@link RefinementTunnels} parked when the proxy opened one, and
 * this route marries the two into a byte pipe. The refinement twin of
 * {@code agenthost/DaemonStreamRoute}; the raw-route, raw-bytes and nonce-is-the-authentication
 * reasoning lives there and applies unchanged.
 */
@ApplicationScoped
public class RefinementStreamRoute {

  private static final Logger LOG = Logger.getLogger(RefinementStreamRoute.class);

  /** Ahead of websockets-next' own registration, so the shared prefix cannot race it. */
  private static final int ROUTE_ORDER = 100;

  @Inject RefinementTunnels tunnels;

  void init(@Observes Router router) {
    router.route(RefinementPaths.STREAM_PREFIX + "*").order(ROUTE_ORDER).handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    if (path.length() < RefinementPaths.STREAM_PREFIX.length()) {
      rc.response().setStatusCode(404).end();
      return;
    }
    String nonce = path.substring(RefinementPaths.STREAM_PREFIX.length());
    RefinementTunnels.Parked parked = tunnels.claim(nonce).orElse(null);
    if (parked == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    rc.request()
        .toWebSocket()
        .onFailure(
            t -> {
              LOG.debugf("refinement stream upgrade failed: %s", String.valueOf(t));
              parked.socket().close();
            })
        .onSuccess(socket -> pipe(socket, parked));
  }

  /**
   * Pump bytes both ways — {@code writeBinaryMessage} toward the daemon (a NetSocket chunk sits at
   * exactly Netty's default max frame size), {@code handler} rather than {@code
   * binaryMessageHandler} from it, early bytes replayed first, pause/drain in both directions.
   */
  private static void pipe(ServerWebSocket remote, RefinementTunnels.Parked parked) {
    NetSocket local = parked.socket();
    if (parked.early().length() > 0) {
      remote.writeBinaryMessage(parked.early());
    }
    remote.handler(
        buffer -> {
          local.write(buffer);
          if (local.writeQueueFull()) {
            remote.pause();
            local.drainHandler(v -> remote.resume());
          }
        });
    local.handler(
        buffer -> {
          remote.writeBinaryMessage(buffer);
          if (remote.writeQueueFull()) {
            local.pause();
            remote.drainHandler(v -> local.resume());
          }
        });
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.exceptionHandler(t -> local.close());
    local.exceptionHandler(t -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
  }
}
