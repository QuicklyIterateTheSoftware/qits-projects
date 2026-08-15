package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
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
 * The endpoint each project's in-container {@code qits-projects-daemon} dials on boot. It owns only
 * the WebSocket lifecycle and JSON framing — {@link AgentDaemonRegistry} owns the state.
 *
 * <p><b>The path is an append-only cross-repo contract.</b> {@link AgentContainerFactory} injects
 * {@code ws://<host>:<port>/projects/daemon/<projectId>} as {@code QITS_PROJECTS_DAEMON_URL} into
 * every container it creates, and the daemon dials exactly that, verbatim, parsing no path out of
 * it. Only a container recreate re-injects the value, so changing this literal breaks every
 * container already running. It is spelled once, in {@link
 * DaemonProtocol#CONTROL_SOCKET_PATH_PREFIX}, and both repos read it from there.
 *
 * <p>Note a {@code @WebSocket} path is a literal that does <b>not</b> follow {@code
 * quarkus.rest.path}, so it carries the {@code /projects} segment itself — and, being outside
 * {@code /projects/api}, it needs its own entry in {@code quarkus.quinoa.ignored-path-prefixes} or
 * a plain GET on it answers the SPA's {@code index.html} with a 200.
 *
 * <p>Unlike qits-workspaces' equivalent the path parameter needs no parsing: a project id is
 * already a String, which is the only type websockets-next accepts for a {@code @PathParam}.
 *
 * <p>The socket requires {@code qits:system} during the upgrade. Its daemon callers present their
 * commissioned machine tokens; the project path parameter selects a target and is not accepted as
 * authentication by itself. The reverse tunnel's nonce remains a second, connection-local guard.
 */
@WebSocket(path = DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + "{projectId}")
@jakarta.annotation.security.RolesAllowed("qits:system")
public class AgentControlSocket {

  private static final Logger LOG = Logger.getLogger(AgentControlSocket.class);

  @Inject AgentDaemonRegistry registry;

  @Inject DaemonMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("projectId") String projectId, WebSocketConnection connection) {
    registry.register(projectId, connection);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message, @PathParam("projectId") String projectId, WebSocketConnection connection) {
    DaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable projects-daemon frame for project %s: %s",
          projectId, e.getMessage());
      return;
    }
    registry.onMessage(projectId, connection, decoded);
  }

  @OnClose
  public void onClose(@PathParam("projectId") String projectId, WebSocketConnection connection) {
    registry.unregister(projectId, connection);
  }
}
