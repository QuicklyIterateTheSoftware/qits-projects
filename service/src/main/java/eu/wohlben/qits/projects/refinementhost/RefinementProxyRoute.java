package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.agenthost.ContainerProxyRoute;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The refinement reverse proxy: {@code /projects/refinement-container/{id}/*} forwards verbatim to
 * that refinement's in-container {@code qits-workspace-daemon}. The refinement twin of
 * {@code agenthost/ContainerProxyRoute} — same no-rewrite rule, same tunnel-only transport, same
 * hand-rolled WebSocket upgrade (the two interceptors it reuses carry the flow-control measurement
 * in their own javadoc).
 *
 * <p>The daemon receives {@code /projects/refinement-container/{id}/commands}, not
 * {@code /commands}: {@link RefinementContainerFactory} injects {@link RefinementPaths#proxyBase}
 * as {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH} at container creation, and the daemon's own
 * base-path check refuses anything outside it — so a rewrite here would 404 every request.
 */
@ApplicationScoped
public class RefinementProxyRoute {

  private static final Logger LOG = Logger.getLogger(RefinementProxyRoute.class);

  @Inject io.vertx.core.Vertx vertx;

  @Inject RefinementTunnels tunnels;

  /** The bearer the daemon requires; the same value {@link RefinementContainerFactory} injects. */
  @ConfigProperty(
      name = "qits.projects.refinement-daemon-api-token",
      defaultValue = "qits-projects-refinement-daemon")
  String daemonApiToken;

  /** The daemon's own port — the authority it is shown, not where the proxy connects. */
  @ConfigProperty(name = "qits.projects.refinement-daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  void init(@Observes Router router) {
    router.route(RefinementPaths.PROXY_PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    int start = RefinementPaths.PROXY_PREFIX.length();
    if (path.length() < start) {
      respond(rc, 404, "No refinement here.");
      return;
    }
    String[] segments = path.substring(start).split("/", 2);
    Long refinementId = parse(segments.length < 1 ? "" : segments[0]);
    if (refinementId == null) {
      respond(rc, 404, "No refinement here.");
      return;
    }
    // The request stays untouched while the lookup runs off the event loop — opening a tunnel
    // awaits a bind on first use. The proxy resumes it when forwarding.
    rc.request().pause();
    rc.vertx()
        .executeBlocking(() -> tunnels.originFor(refinementId).orElse(null))
        .onFailure(e -> respond(rc, 502, "The refinement container could not be reached."))
        .onSuccess(origin -> route(rc, origin));
  }

  private void route(RoutingContext rc, RefinementTunnels.TunnelOrigin origin) {
    if (origin == null) {
      respond(
          rc,
          502,
          "The refinement container is not running — start it from the status strip.");
      return;
    }
    forward(rc, origin.client(), origin.port());
  }

  private void forward(RoutingContext rc, HttpClient client, int port) {
    if (isWebSocketUpgrade(rc.request())) {
      proxyUpgrade(rc, client, port);
      return;
    }
    HttpProxy.reverseProxy(client)
        .origin(port, "127.0.0.1")
        .addInterceptor(ContainerProxyRoute.bearer(daemonApiToken))
        .addInterceptor(ContainerProxyRoute.hostRewrite(daemonApiPort))
        .handle(rc.request());
  }

  private static boolean isWebSocketUpgrade(HttpServerRequest request) {
    return request.headers().contains(HttpHeaders.UPGRADE, "websocket", true);
  }

  /** The hand-rolled upgrade — see {@code ContainerProxyRoute.proxyUpgrade} for the measurement. */
  private void proxyUpgrade(RoutingContext rc, HttpClient client, int port) {
    HttpServerRequest inbound = rc.request();
    client
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("127.0.0.1")
                .setPort(port)
                .setURI(inbound.uri()))
        .onFailure(
            failure -> {
              LOG.debugf("refinement upgrade could not be opened: %s", String.valueOf(failure));
              inbound.resume();
              respond(rc, 502, "The refinement container is not reachable — try restarting it.");
            })
        .onSuccess(outbound -> openUpgrade(rc, inbound, outbound));
  }

  private void openUpgrade(
      RoutingContext rc, HttpServerRequest inbound, HttpClientRequest outbound) {
    for (Map.Entry<String, String> header : inbound.headers()) {
      if (HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().set(HttpHeaders.CONNECTION, HttpHeaders.UPGRADE);
      } else if (!HttpHeaders.HOST.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().add(header.getKey(), header.getValue());
      }
    }
    outbound.headers().set("Authorization", "Bearer " + daemonApiToken);

    Future<HttpClientResponse> handshake = outbound.connect();
    inbound.handler(
        buffer -> {
          outbound.write(buffer);
          if (outbound.writeQueueFull()) {
            inbound.pause();
            outbound.drainHandler(drained -> inbound.resume());
          }
        });
    inbound.endHandler(end -> outbound.end());
    inbound.resume();

    handshake
        .onFailure(
            failure -> {
              LOG.debugf("refinement daemon refused the upgrade: %s", String.valueOf(failure));
              respond(rc, 502, "The refinement container closed the connection.");
            })
        .onSuccess(response -> completeUpgrade(rc, inbound, response));
  }

  private void completeUpgrade(
      RoutingContext rc, HttpServerRequest inbound, HttpClientResponse response) {
    HttpServerResponse out = inbound.response();
    if (response.statusCode() != 101) {
      out.setStatusCode(response.statusCode());
      for (Map.Entry<String, String> header : response.headers()) {
        if (!HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())
            && !HttpHeaders.TRANSFER_ENCODING.toString().equalsIgnoreCase(header.getKey())) {
          out.headers().add(header.getKey(), header.getValue());
        }
      }
      response.pipeTo(out);
      return;
    }
    out.setStatusCode(101);
    out.headers().addAll(response.headers());
    NetSocket daemon = response.netSocket();
    inbound
        .toNetSocket()
        .onFailure(
            failure -> {
              LOG.debugf("could not take over the browser socket: %s", String.valueOf(failure));
              daemon.close();
            })
        .onSuccess(browser -> pipe(browser, daemon));
  }

  private static void pipe(NetSocket browser, NetSocket daemon) {
    browser.handler(
        buffer -> {
          daemon.write(buffer);
          if (daemon.writeQueueFull()) {
            browser.pause();
            daemon.drainHandler(drained -> browser.resume());
          }
        });
    daemon.handler(
        buffer -> {
          browser.write(buffer);
          if (browser.writeQueueFull()) {
            daemon.pause();
            browser.drainHandler(drained -> daemon.resume());
          }
        });
    browser.endHandler(end -> daemon.close());
    daemon.endHandler(end -> browser.close());
    browser.closeHandler(closed -> daemon.close());
    daemon.closeHandler(closed -> browser.close());
    browser.exceptionHandler(failure -> daemon.close());
    daemon.exceptionHandler(failure -> browser.close());
  }

  private static Long parse(String segment) {
    if (segment.isEmpty()) {
      return null;
    }
    try {
      return Long.valueOf(segment);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void respond(RoutingContext rc, int status, String message) {
    rc.response()
        .setStatusCode(status)
        .putHeader("Content-Type", "application/json")
        .end(new io.vertx.core.json.JsonObject().put("message", message).encode());
  }
}
