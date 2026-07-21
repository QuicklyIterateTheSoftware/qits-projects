package eu.wohlben.qits.domain.repository.control;

import com.pty4j.PtyProcessBuilder;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.error.InternalServerErrorException;
import eu.wohlben.qits.domain.process.control.RepoReservation;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-memory registry of live remote-login sign-in terminals — one {@link RemoteLoginSession}
 * per repository at most, spawned on the first WebSocket attach (the socket-only precedent of
 * {@code DaemonTerminalSocket}: no REST trigger, so a dialog re-open or the terminal's
 * auto-reconnect simply re-attaches with replay instead of killing git mid-prompt).
 *
 * <p>The session runs exactly the interactive form of {@code RepositoryService#pushRepository}'s
 * push — same credential-store flags, same refspec, in the bare origin — but in a host-side PTY
 * with terminal prompting <em>enabled</em>: git prompts for username/password, and on success the
 * branch is pushed <em>and</em> the store persists the credentials, so sign-in and retry are one
 * step and every later non-interactive remote verb just works.
 *
 * <p><b>Single-flight.</b> Each session holds a repository-scoped {@link
 * TechnicalProcessRegistry#reserveRepository reservation} of kind {@code remote-login}, so a
 * login-push can never race a concurrent pull/sync/push on the same bare origin — every cross-kind
 * collision is an honest {@link OpenResult.Refused}. A reservation (unlike the earlier guard
 * process) is <em>not</em> a streamed {@code TechnicalProcess}: it is immune to the idle reaper (so
 * a terminal idling at git's prompt is never force-finished out from under the live push) and is
 * invisible to active-process discovery (so a reload opens no empty process dialog). The
 * reservation is released the instant the PTY exits.
 *
 * <p><b>Routing.</b> {@link #open} returns a {@link Handle} bound to the exact session the caller
 * attached to; the WebSocket keeps that handle and drives input/resize/detach through it — so a
 * stale connection whose close is still in flight can never deliver keystrokes into a
 * <em>different</em> session that has since been spawned for the same repository.
 *
 * <p><b>Backstop.</b> A session whose last client detaches is terminated after a linger window (a
 * reattach cancels it); the window is guarded by a per-schedule token so a reattach that races the
 * timer's firing wins.
 */
@ApplicationScoped
public class RemoteLoginSessions {

  private static final Logger LOG = Logger.getLogger(RemoteLoginSessions.class);

  /** The reservation kind — see {@code TechnicalProcessRegistry.reserveRepository}. */
  public static final String KIND_REMOTE_LOGIN = "remote-login";

  /** The outcome of an attach attempt. */
  public sealed interface OpenResult {
    /** Attached — the {@code handle} drives input/resize/detach for this connection. */
    record Opened(Handle handle) implements OpenResult {}

    /** The repository is busy with {@code runningKind}; nothing was attached. */
    record Refused(String runningKind) implements OpenResult {}
  }

  /**
   * How long a session with no attached client stays alive before it is terminated (a reattach
   * cancels the timer) — long enough for a dialog re-open or a network blip's reconnect, short
   * enough that an abandoned prompt doesn't hold the repository's single-flight for long.
   */
  @ConfigProperty(name = "qits.repositories.remote-login-linger-ms", defaultValue = "60000")
  long lingerMillis;

  @Inject RepositoryService repositories;

  @Inject GitRemoteAuth remoteAuth;

  @Inject TechnicalProcessRegistry processes;

  /** Live sessions with the reservation token they hold, keyed by repository id. */
  private final Map<String, Entry> entries = new HashMap<>();

  private record Entry(RemoteLoginSession session, String reservationToken) {}

  /** The current linger schedule per repo: an identity token plus its future (for cancellation). */
  private final Map<String, Object> lingerTokens = new HashMap<>();

  private final Map<String, ScheduledFuture<?>> lingerFutures = new HashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "remote-login-sessions");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * A per-connection routing handle: everything the WebSocket does after attach goes through the
   * exact session it attached to, never a repoId re-lookup that could resolve to a newer session.
   */
  public final class Handle {
    private final RemoteLoginSession session;
    private final CommandOutputSink sink;

    private Handle(RemoteLoginSession session, CommandOutputSink sink) {
      this.session = session;
      this.sink = sink;
    }

    public void input(byte[] data) {
      session.input(data);
    }

    public void resize(int cols, int rows) {
      session.resize(cols, rows);
    }

    /** Detach this connection; arms the linger backstop if it was the session's last client. */
    public void detach() {
      detachClient(session, sink);
    }
  }

  /**
   * Attach {@code sink} to the repository's sign-in terminal, spawning the session when none is
   * live. A live session is attached with full scrollback replay; when there is none and the
   * repository is busy with another operation, the attach is {@link OpenResult.Refused} and nothing
   * is spawned. {@code onSessionEnd} fires with the process exit code when the session ends
   * (immediately for a just-finished one). On success the returned {@link Handle} must be used for
   * all later input/resize/detach.
   */
  public OpenResult open(String repoId, CommandOutputSink sink, IntConsumer onSessionEnd) {
    RemoteLoginSession session;
    boolean created = false;
    synchronized (this) {
      Entry existing = entries.get(repoId);
      if (existing != null) {
        cancelLinger(repoId);
        session = existing.session();
      } else {
        RepoReservation reservation = processes.reserveRepository(repoId, KIND_REMOTE_LOGIN);
        if (reservation instanceof RepoReservation.Conflict conflict) {
          return new OpenResult.Refused(conflict.runningKind());
        }
        String token = ((RepoReservation.Acquired) reservation).token();
        try {
          session = spawn(repoId, token);
        } catch (RuntimeException e) {
          // Never leak the reservation if the PTY failed to start / the repo vanished.
          processes.releaseRepository(repoId, token);
          throw e;
        }
        entries.put(repoId, new Entry(session, token));
        created = true;
      }
    }
    // Replay (a blocking write of up to the ring size) happens OUTSIDE the registry monitor so one
    // slow client can't stall open/detach/session-end for every repository.
    session.attach(sink, onSessionEnd);
    if (created) {
      session.startReader();
    }
    // The connection may have closed during the blocking attach (the WebSocket's onClose would then
    // have found no handle to detach): reconcile now so the linger backstop still arms.
    if (!sink.isOpen()) {
      detachClient(session, sink);
    }
    return new OpenResult.Opened(new Handle(session, sink));
  }

  /**
   * Detach a client without ending the session (it lingers for a reattach); once no client remains,
   * the linger timer arms and terminates the session when it elapses.
   */
  private synchronized void detachClient(RemoteLoginSession session, CommandOutputSink sink) {
    int remaining = session.detach(sink);
    if (remaining == 0 && session.isAlive()) {
      armLinger(session.repoId());
    }
  }

  private RemoteLoginSession spawn(String repoId, String reservationToken) {
    RepositoryService.PushSpec spec = repositories.pushSpec(repoId); // 404 for an unknown repo
    try {
      Map<String, String> env = new HashMap<>(System.getenv());
      env.put("TERM", "xterm-256color");
      // Strip inherited prompt-diverting vars: an ambient GIT_ASKPASS (VS Code's integrated
      // terminal — the documented quarkus:dev launch — sets it), SSH_ASKPASS, or
      // GIT_TERMINAL_PROMPT=0
      // would send git to a headless askpass or suppress the prompt entirely, defeating the very
      // interactive sign-in this PTY exists to show.
      env.remove("GIT_ASKPASS");
      env.remove("SSH_ASKPASS");
      env.remove("GIT_TERMINAL_PROMPT");
      var pty =
          new PtyProcessBuilder()
              .setCommand(
                  remoteAuth.gitWithCredentials(
                      "push",
                      spec.url(),
                      "refs/heads/" + spec.branch() + ":refs/heads/" + spec.branch()))
              .setDirectory(spec.originPath().toString())
              .setEnvironment(env)
              .setInitialColumns(80)
              .setInitialRows(24)
              .start();
      RemoteLoginSession session =
          new RemoteLoginSession(
              repoId, pty, exitCode -> onSessionFinished(repoId, reservationToken));
      session.seedBanner(
          "Signing in to "
              + spec.url()
              + "\r\ngit will prompt for a username and password — use a scoped personal access"
              + " token (PAT) rather than your account password. Successful credentials are stored"
              + " for every later push/pull against this host.\r\n\r\n");
      return session;
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to start sign-in terminal: " + e.getMessage());
    }
  }

  /**
   * The PTY exited: drop the session and release its reservation (clearing the single-flight). Runs
   * before the per-client socket closes (see {@link RemoteLoginSession#finish}), so release never
   * waits on client I/O. No verdict is emitted — the terminal shows git's real exit and the dialog
   * refetches sync-status on close, so a failed sign-in never masquerades as a green success.
   */
  private synchronized void onSessionFinished(String repoId, String reservationToken) {
    Entry entry = entries.get(repoId);
    if (entry != null && reservationToken.equals(entry.reservationToken())) {
      entries.remove(repoId);
      cancelLinger(repoId);
    }
    processes.releaseRepository(repoId, reservationToken); // idempotent: only if still current
  }

  /** Arm (or re-arm) the linger backstop for a now-unattended session. Caller holds the monitor. */
  private void armLinger(String repoId) {
    cancelLinger(repoId);
    Object token = new Object();
    lingerTokens.put(repoId, token);
    lingerFutures.put(
        repoId,
        scheduler.schedule(
            () -> terminateIfUnattended(repoId, token), lingerMillis, TimeUnit.MILLISECONDS));
  }

  /**
   * The linger window elapsed — kill the session only if it is still the same, still unattended
   * one. The {@code lingerToken} guards against a reattach that raced this firing: {@code
   * cancelLinger} clears the token under the monitor, so a timer whose {@code Future.cancel(false)}
   * came too late to stop it still sees a stale token here and bails. Removal + release happen
   * atomically under the monitor (so a racing {@link #open} spawns a fresh session rather than
   * attaching to a dying one); the blocking {@code terminate()} runs outside it.
   */
  private void terminateIfUnattended(String repoId, Object lingerToken) {
    RemoteLoginSession toKill = null;
    synchronized (this) {
      if (lingerTokens.get(repoId) != lingerToken) {
        return; // superseded by a reattach or a later detach
      }
      Entry entry = entries.get(repoId);
      if (entry != null && !entry.session().hasClients() && entry.session().isAlive()) {
        entries.remove(repoId);
        cancelLinger(repoId);
        processes.releaseRepository(repoId, entry.reservationToken());
        toKill = entry.session();
      }
    }
    if (toKill != null) {
      LOG.infof("Terminating unattended sign-in terminal for repository %s", repoId);
      toKill.terminate();
    }
  }

  private void cancelLinger(String repoId) {
    lingerTokens.remove(repoId);
    ScheduledFuture<?> timer = lingerFutures.remove(repoId);
    if (timer != null) {
      timer.cancel(false);
    }
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
    List<RemoteLoginSession> live;
    synchronized (this) {
      live = entries.values().stream().map(Entry::session).toList();
    }
    live.forEach(RemoteLoginSession::terminate);
  }
}
