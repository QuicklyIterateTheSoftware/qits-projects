package eu.wohlben.qits.domain.repository.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import eu.wohlben.qits.domain.repository.persistence.WorktreeRepository;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Streams an interactive {@code bash} running inside a worktree checkout to the browser. The server
 * attaches bash to a real pseudo-terminal (pty4j) so it behaves like a true TTY — echo, line
 * editing, colors and full-screen apps all work — and the browser renders the stream with xterm.js.
 *
 * <p>The socket is keyed by {@code worktreeId} (a safe id, never containing slashes) rather than
 * the branch name. The worktree dir on disk follows the same convention as {@link
 * eu.wohlben.qits.domain.repository.control.WorktreeService}: {@code
 * <data-dir>/<repoId>/worktrees/<worktreeId>}.
 *
 * <p>Wire protocol: the client sends JSON envelopes — {@code {"type":"data","data":"..."}} for
 * keystrokes and {@code {"type":"resize","cols":N,"rows":M}} for terminal size. The server sends
 * raw PTY output back as text frames (already terminal-encoded; xterm.js writes them verbatim).
 */
@WebSocket(path = "/api/terminal/{repoId}/{worktreeId}")
public class TerminalSocket {

  private static final Logger LOG = Logger.getLogger(TerminalSocket.class);

  /** Per-connection PTY session, keyed by {@link WebSocketConnection#id()}. */
  private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

  @Inject WorktreeRepository worktreeRepository;

  @Inject ObjectMapper objectMapper;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @OnOpen
  @RunOnVirtualThread
  @Transactional
  public void onOpen(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      WebSocketConnection connection) {
    // Never build a filesystem path from unvalidated input: the worktree must be a known row for
    // this repo. This also rejects path-traversal attempts in worktreeId.
    if (!worktreeRepository.existsByRepositoryAndWorktreeId(repoId, worktreeId)) {
      connection.sendTextAndAwait("Worktree not found: " + worktreeId + "\r\n");
      connection.closeAndAwait();
      return;
    }

    Path worktreePath = Path.of(dataDir, repoId, "worktrees", worktreeId).toAbsolutePath();
    if (!Files.exists(worktreePath)) {
      connection.sendTextAndAwait("Worktree checkout missing on disk\r\n");
      connection.closeAndAwait();
      return;
    }

    try {
      Map<String, String> env = new HashMap<>(System.getenv());
      env.put("TERM", "xterm-256color");

      PtyProcess process =
          new PtyProcessBuilder()
              .setCommand(new String[] {"/bin/bash", "-l"})
              .setDirectory(worktreePath.toString())
              .setEnvironment(env)
              .setInitialColumns(80)
              .setInitialRows(24)
              .start();

      TerminalSession session = new TerminalSession(process);
      sessions.put(connection.id(), session);

      // Pump PTY output → browser on a dedicated daemon thread. sendTextAndAwait blocks until the
      // frame is flushed, which naturally throttles a chatty process to the socket's pace.
      Thread reader =
          new Thread(
              () -> pumpOutput(process, connection),
              "terminal-" + worktreeId + "-" + connection.id());
      reader.setDaemon(true);
      session.reader = reader;
      reader.start();
    } catch (IOException e) {
      LOG.errorf(e, "Failed to start terminal for worktree %s/%s", repoId, worktreeId);
      connection.sendTextAndAwait("Failed to start terminal: " + e.getMessage() + "\r\n");
      connection.closeAndAwait();
    }
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    TerminalSession session = sessions.get(connection.id());
    if (session == null) {
      return;
    }
    try {
      JsonNode node = objectMapper.readTree(message);
      String type = node.path("type").asText();
      if ("data".equals(type)) {
        OutputStream out = session.process.getOutputStream();
        out.write(node.path("data").asText().getBytes(StandardCharsets.UTF_8));
        out.flush();
      } else if ("resize".equals(type)) {
        int cols = node.path("cols").asInt(80);
        int rows = node.path("rows").asInt(24);
        session.process.setWinSize(new WinSize(cols, rows));
      }
    } catch (IOException e) {
      LOG.debugf(e, "Terminal write failed for connection %s", connection.id());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    TerminalSession session = sessions.remove(connection.id());
    if (session != null) {
      session.close();
    }
  }

  private void pumpOutput(PtyProcess process, WebSocketConnection connection) {
    byte[] buffer = new byte[4096];
    try (InputStream in = process.getInputStream()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (!connection.isOpen()) {
          break;
        }
        connection.sendTextAndAwait(new String(buffer, 0, read, StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      LOG.debugf(e, "Terminal output pump ended for connection %s", connection.id());
    } finally {
      if (connection.isOpen()) {
        connection.closeAndAwait();
      }
    }
  }

  /** Holds the spawned process and its output-pump thread for one browser connection. */
  private static final class TerminalSession {
    final PtyProcess process;
    Thread reader;

    TerminalSession(PtyProcess process) {
      this.process = process;
    }

    void close() {
      // A login `bash -l` ignores the SIGHUP that destroy() sends, so force a SIGKILL — otherwise
      // the
      // shell outlives the browser tab and leaks. Closing the PTY streams also unblocks the reader
      // thread, which is otherwise parked in a native read() that interrupt() can't break.
      process.destroyForcibly();
      try {
        process.getInputStream().close();
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // Best-effort: the process is already being killed.
      }
      if (reader != null) {
        reader.interrupt();
      }
    }
  }
}
