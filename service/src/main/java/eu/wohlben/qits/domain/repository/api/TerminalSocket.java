package eu.wohlben.qits.domain.repository.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
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
 * Attaches a browser to a running registry command and streams its PTY to xterm.js. The process is
 * owned by the {@link CommandRegistry}, decoupled from this connection: opening the socket only
 * <em>attaches</em> (replaying the scrollback so the user picks up where they left off), and
 * closing it only <em>detaches</em> — the process keeps running until it exits or is explicitly
 * terminated. Re-opening the socket for the same command id re-attaches to the same process.
 *
 * <p>Wire protocol: the client sends JSON envelopes — {@code {"type":"data","data":"..."}} for
 * keystrokes and {@code {"type":"resize","cols":N,"rows":M}} for terminal size. The server sends
 * raw PTY output back as text frames (already terminal-encoded; xterm.js writes them verbatim).
 */
@WebSocket(path = "/api/terminal/commands/{commandId}")
public class TerminalSocket {

  private static final Logger LOG = Logger.getLogger(TerminalSocket.class);

  /** The sink registered with the registry per connection, so onClose can detach the same one. */
  private final Map<String, CommandOutputSink> sinks = new ConcurrentHashMap<>();

  @Inject CommandRegistry registry;

  @Inject ObjectMapper objectMapper;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("commandId") String commandId, WebSocketConnection connection) {
    CommandOutputSink sink = new ConnectionSink(connection);
    if (!registry.attach(commandId, sink)) {
      connection.sendTextAndAwait("\r\n\u001b[33mThis command is no longer running.\u001b[0m\r\n");
      connection.closeAndAwait();
      return;
    }
    sinks.put(connection.id(), sink);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message, @PathParam("commandId") String commandId, WebSocketConnection connection) {
    if (!sinks.containsKey(connection.id())) {
      return;
    }
    try {
      JsonNode node = objectMapper.readTree(message);
      String type = node.path("type").asText();
      if ("data".equals(type)) {
        registry.input(commandId, node.path("data").asText().getBytes(StandardCharsets.UTF_8));
      } else if ("resize".equals(type)) {
        registry.resize(commandId, node.path("cols").asInt(80), node.path("rows").asInt(24));
      }
    } catch (IOException e) {
      LOG.debugf(e, "Terminal message parse failed for connection %s", connection.id());
    }
  }

  @OnClose
  public void onClose(@PathParam("commandId") String commandId, WebSocketConnection connection) {
    CommandOutputSink sink = sinks.remove(connection.id());
    if (sink != null) {
      // Detach only — never kill. The process outlives the browser tab until it exits or the user
      // explicitly terminates it from the Commands UI.
      registry.detach(commandId, sink);
    }
  }

  /** Bridges a websocket connection to the registry's framework-free output sink. */
  private static final class ConnectionSink implements CommandOutputSink {
    private final WebSocketConnection connection;

    ConnectionSink(WebSocketConnection connection) {
      this.connection = connection;
    }

    @Override
    public void write(String data) {
      // Blocks until the frame is flushed, naturally throttling a chatty process to the socket
      // pace.
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
