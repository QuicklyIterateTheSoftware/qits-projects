package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The endpoint each refinement container's in-container {@code qits-workspace-daemon} dials on
 * boot. It owns only the WebSocket lifecycle and JSON framing — {@link RefinementDaemonRegistry}
 * owns the state. The refinement twin of {@code agenthost/AgentControlSocket}, speaking the
 * <b>workspace</b> daemon's protocol.
 *
 * <p><b>The path is append-only.</b> {@link RefinementContainerFactory} injects
 * {@code ws://<host>:<port>/projects/refinement-daemon/<rowId>} as {@code QITS_WORKSPACE_DAEMON_URL}
 * into every container it creates, and the daemon dials exactly that, verbatim. Only a container
 * recreate re-injects the value.
 *
 * <p>The row id arrives as a String — the only type websockets-next accepts for a
 * {@code @PathParam} — and is parsed here, the same manual move qits-workspaces makes. A path that
 * is not a number registers nothing and the socket is left to idle out.
 *
 * <p>The socket requires {@code qits:system} during the upgrade; the daemon presents its
 * commissioned machine token ({@code QITS_WORKSPACE_DAEMON_AUTH_*}). The row-id path parameter
 * selects a target and is not accepted as authentication by itself — the reverse tunnel's nonce
 * remains a second, connection-local guard.
 */
@WebSocket(path = RefinementPaths.CONTROL_SOCKET_PREFIX + "{refinementId}")
@jakarta.annotation.security.RolesAllowed("qits:system")
public class RefinementControlSocket {

  private static final Logger LOG = Logger.getLogger(RefinementControlSocket.class);

  @Inject RefinementDaemonRegistry registry;

  @Inject RefinementMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("refinementId") String refinementId, WebSocketConnection connection) {
    Long id = parse(refinementId);
    if (id == null) {
      LOG.debugf("workspace-daemon dialled a non-numeric refinement id '%s'", refinementId);
      return;
    }
    registry.register(id, connection);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message,
      @PathParam("refinementId") String refinementId,
      WebSocketConnection connection) {
    Long id = parse(refinementId);
    if (id == null) {
      return;
    }
    DaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      // Dropped, never fatal: a daemon must not be able to break its own control socket by saying
      // something this host cannot parse — the workspace protocol's own rule, kept here.
      LOG.debugf(
          "Dropped an undecodable workspace-daemon frame for refinement %s: %s",
          refinementId, e.getMessage());
      return;
    }
    registry.onMessage(id, connection, decoded);
  }

  @OnClose
  public void onClose(
      @PathParam("refinementId") String refinementId, WebSocketConnection connection) {
    Long id = parse(refinementId);
    if (id != null) {
      registry.unregister(id, connection);
    }
  }

  private static Long parse(String refinementId) {
    try {
      return Long.valueOf(refinementId);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
