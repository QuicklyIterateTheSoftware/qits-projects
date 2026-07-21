package eu.wohlben.qits.domain.repository.control;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.jboss.logging.Logger;

/**
 * One live <em>host-side</em> PTY running the interactive sign-in push — {@code CommandSession}
 * minus the container coupling (no {@code docker exec} prefix, no pid-file group kill, no audit
 * log): pty4j starts the child as the session leader on its own controlling terminal, so {@code
 * destroy()}/{@code destroyForcibly()} is complete termination. Same replay contract as the
 * registry sessions: a single reader thread drains the PTY into a bounded ring and fans chunks out
 * to attached {@link CommandOutputSink}s under the session monitor, so a freshly attached sink
 * never interleaves replayed and live output.
 *
 * <p>Each attach registers an end-listener alongside its sink; when the process exits, every
 * listener receives the exit code (the socket sends a closing note and shuts the connection).
 * Attaching to an already-finished session replays the ring and fires the listener immediately.
 */
final class RemoteLoginSession {

  private static final Logger LOG = Logger.getLogger(RemoteLoginSession.class);

  /** How much recent raw output to retain for replay on re-attach. */
  private static final int RING_CAPACITY_BYTES = 256 * 1024;

  /** How long after SIGHUP ({@code destroy}) before escalating to SIGKILL. */
  private static final long TERMINATE_GRACE_MILLIS = 2000;

  private final String repoId;
  private final PtyProcess process;

  /**
   * Registry-level completion (release the single-flight, drop the session), given the exit code.
   * Run <em>before</em> the per-client end-listeners so guard release never waits on a client
   * socket write (see {@link #finish}).
   */
  private final IntConsumer onFinished;

  /** Recent raw output chunks, total bounded by {@link #RING_CAPACITY_BYTES}, for replay. */
  private final Deque<byte[]> ring = new ArrayDeque<>();

  private int ringBytes;

  /** Attached sinks with their end-listeners; mutated and iterated only under the monitor. */
  private final Map<CommandOutputSink, IntConsumer> attachments = new LinkedHashMap<>();

  /** Serializes stdin writes from concurrent clients so their bytes don't interleave. */
  private final Object stdinLock = new Object();

  private boolean terminal;
  private int exitCode = -1;

  RemoteLoginSession(String repoId, PtyProcess process, IntConsumer onFinished) {
    this.repoId = repoId;
    this.process = process;
    this.onFinished = onFinished;
  }

  String repoId() {
    return repoId;
  }

  /**
   * Whether any client is currently attached — the linger backstop must not kill a live terminal.
   */
  synchronized boolean hasClients() {
    return !attachments.isEmpty();
  }

  /** Seed intro text into the ring before the reader starts, so every attach replays it first. */
  synchronized void seedBanner(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    ring.add(bytes);
    ringBytes += bytes.length;
  }

  void startReader() {
    Thread t = new Thread(this::readLoop, "remote-login-" + repoId);
    t.setDaemon(true);
    t.start();
  }

  private void readLoop() {
    byte[] buffer = new byte[4096];
    try (InputStream in = process.getInputStream()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
        synchronized (this) {
          appendToRing(buffer, read);
          broadcast(text);
        }
      }
    } catch (Exception e) {
      LOG.debugf(e, "Output pump ended for remote login of repository %s", repoId);
    } finally {
      finish();
    }
  }

  /**
   * Attach a client: replay the buffered scrollback, then receive live output; {@code endListener}
   * fires with the exit code when the process ends (immediately if it already has).
   */
  synchronized void attach(CommandOutputSink sink, IntConsumer endListener) {
    if (ringBytes > 0) {
      byte[] snapshot = new byte[ringBytes];
      int pos = 0;
      for (byte[] chunk : ring) {
        System.arraycopy(chunk, 0, snapshot, pos, chunk.length);
        pos += chunk.length;
      }
      try {
        sink.write(new String(snapshot, StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        LOG.debugf(e, "Replay failed for remote login of repository %s", repoId);
        return;
      }
    }
    if (terminal) {
      endListener.accept(exitCode);
      return;
    }
    attachments.put(sink, endListener);
  }

  /** Detach a client; returns how many sinks remain attached (for the registry's linger timer). */
  synchronized int detach(CommandOutputSink sink) {
    attachments.remove(sink);
    return attachments.size();
  }

  /** Forward a client's keystrokes to the process stdin. */
  void input(byte[] data) {
    synchronized (stdinLock) {
      try {
        OutputStream out = process.getOutputStream();
        out.write(data);
        out.flush();
      } catch (IOException e) {
        LOG.debugf(e, "stdin write failed for remote login of repository %s", repoId);
      }
    }
  }

  void resize(int cols, int rows) {
    try {
      process.setWinSize(new WinSize(cols, rows));
    } catch (RuntimeException e) {
      LOG.debugf(e, "resize failed for remote login of repository %s", repoId);
    }
  }

  boolean isAlive() {
    return process.isAlive();
  }

  /**
   * Kill the host process: SIGHUP first (the terminal-close signal — git exits cleanly on it), then
   * SIGKILL after a short grace, and close the streams to unblock the reader's native read.
   */
  void terminate() {
    process.destroy();
    if (!waitQuietly(TERMINATE_GRACE_MILLIS)) {
      process.destroyForcibly();
    }
    try {
      process.getInputStream().close();
      process.getOutputStream().close();
    } catch (IOException ignored) {
      // Best-effort: the process is already being killed.
    }
  }

  private boolean waitQuietly(long millis) {
    try {
      return process.waitFor(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void finish() {
    int code;
    try {
      // Wait for the OS process to be reaped so the exit code is reliable (PTY EOF can precede it).
      code = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      code = -1;
    }
    List<IntConsumer> listeners;
    synchronized (this) {
      terminal = true;
      exitCode = code;
      listeners = new ArrayList<>(attachments.values());
      attachments.clear();
    }
    // Registry cleanup FIRST (fast, non-blocking): release the single-flight the instant git exits,
    // so it can never be gated behind a per-client socket write to a silently-dead connection.
    try {
      onFinished.accept(code);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Session-finished callback failed for remote login of repository %s", repoId);
    }
    // Then notify the clients (each closes its socket — may block on a dead connection, but nothing
    // important waits on this any more).
    for (IntConsumer listener : listeners) {
      try {
        listener.accept(code);
      } catch (RuntimeException e) {
        LOG.debugf(e, "End listener failed for remote login of repository %s", repoId);
      }
    }
  }

  private void appendToRing(byte[] buffer, int read) {
    byte[] chunk = new byte[read];
    System.arraycopy(buffer, 0, chunk, 0, read);
    ring.add(chunk);
    ringBytes += read;
    while (ringBytes > RING_CAPACITY_BYTES && ring.size() > 1) {
      ringBytes -= ring.removeFirst().length;
    }
  }

  private void broadcast(String text) {
    for (Iterator<CommandOutputSink> it = attachments.keySet().iterator(); it.hasNext(); ) {
      CommandOutputSink sink = it.next();
      if (!sink.isOpen()) {
        it.remove();
        continue;
      }
      try {
        sink.write(text);
      } catch (RuntimeException e) {
        it.remove();
      }
    }
  }
}
