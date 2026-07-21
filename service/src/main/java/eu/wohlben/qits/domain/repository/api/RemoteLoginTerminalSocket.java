package eu.wohlben.qits.domain.repository.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.repository.control.RemoteLoginSessions;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The repository sign-in terminal: attaches xterm.js to the host-side interactive {@code git push}
 * whose prompt round-trip fills the shared credential store (see {@link RemoteLoginSessions}).
 * Socket-only — {@code onOpen} spawns the session when none is live and attaches with scrollback
 * replay otherwise, so a dialog re-open or the web terminal's auto-reconnect resumes the same git
 * prompt instead of killing it. {@code onClose} only detaches (the session lingers for reattach);
 * the session ends when git exits, when the linger window elapses unattended, or on conflict
 * cleanup — never because a browser tab went away mid-prompt.
 *
 * <p>Wire protocol matches {@code TerminalSocket}: the client sends {@code
 * {"type":"data","data":…}} for keystrokes and {@code {"type":"resize","cols":N,"rows":M}} for
 * size; the server sends raw PTY output as text frames, an exit note, then a clean close (1000) —
 * the frontend's signal to refetch sync-status. Cross-origin handshakes are rejected globally by
 * {@code SameOriginUpgradeCheck}; the path is not in {@code PublicPaths}, so the upgrade requires
 * an authenticated identity.
 */
@WebSocket(path = "/api/terminal/repositories/{repoId}/remote-login")
public class RemoteLoginTerminalSocket {

  private static final Logger LOG = Logger.getLogger(RemoteLoginTerminalSocket.class);

  /** The per-connection routing handle, so input/resize/detach hit the exact attached session. */
  private final Map<String, RemoteLoginSessions.Handle> handles = new ConcurrentHashMap<>();

  @Inject RemoteLoginSessions sessions;

  @Inject ObjectMapper objectMapper;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("repoId") String repoId, WebSocketConnection connection) {
    ConnectionSink sink = new ConnectionSink(connection);
    RemoteLoginSessions.OpenResult result;
    try {
      result =
          sessions.open(
              repoId,
              sink,
              exitCode -> {
                if (connection.isOpen()) {
                  connection.sendTextAndAwait(
                      "\r\n\u001b[33m[sign-in terminal exited (code "
                          + exitCode
                          + ")]\u001b[0m\r\n");
                  connection.closeAndAwait();
                }
              });
    } catch (RuntimeException e) {
      // Unknown repo / spawn failure — surface in-band and close cleanly (no reconnect storm).
      LOG.debugf(e, "Remote login open failed for repository %s", repoId);
      connection.sendTextAndAwait("\r\n\u001b[33m" + e.getMessage() + "\u001b[0m\r\n");
      connection.closeAndAwait();
      return;
    }
    if (result instanceof RemoteLoginSessions.OpenResult.Refused refused) {
      connection.sendTextAndAwait(
          "\r\n\u001b[33mThis repository is busy (a "
              + refused.runningKind()
              + " is running); try again once it finishes.\u001b[0m\r\n");
      connection.closeAndAwait();
      return;
    }
    RemoteLoginSessions.Handle handle = ((RemoteLoginSessions.OpenResult.Opened) result).handle();
    // The connection may already be closing (onClose can race a blocking onOpen): only register a
    // handle for a live connection, else detach it now so the session's linger backstop still arms
    // and the handle map never leaks a dead connection.
    if (connection.isOpen()) {
      handles.put(connection.id(), handle);
    } else {
      handle.detach();
    }
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    RemoteLoginSessions.Handle handle = handles.get(connection.id());
    if (handle == null) {
      return;
    }
    try {
      JsonNode node = objectMapper.readTree(message);
      String type = node.path("type").asText();
      if ("data".equals(type)) {
        handle.input(node.path("data").asText().getBytes(StandardCharsets.UTF_8));
      } else if ("resize".equals(type)) {
        handle.resize(node.path("cols").asInt(80), node.path("rows").asInt(24));
      }
    } catch (IOException e) {
      LOG.debugf(e, "Remote login message parse failed for connection %s", connection.id());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    RemoteLoginSessions.Handle handle = handles.remove(connection.id());
    if (handle != null) {
      // Detach only — the session lingers for a reattach; abandonment is handled by the registry's
      // linger timer, not by a tab close.
      handle.detach();
    }
  }

  /** Bridges a websocket connection to the framework-free output sink. */
  private static final class ConnectionSink implements CommandOutputSink {
    private final WebSocketConnection connection;

    ConnectionSink(WebSocketConnection connection) {
      this.connection = connection;
    }

    @Override
    public void write(String data) {
      if (connection.isOpen()) {
        connection.sendTextAndAwait(data);
      }
    }

    @Override
    public boolean isOpen() {
      return connection.isOpen();
    }
  }
}
