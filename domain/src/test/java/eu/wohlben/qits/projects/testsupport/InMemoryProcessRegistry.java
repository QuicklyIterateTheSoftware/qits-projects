package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.RepoProcessLease;
import eu.wohlben.qits.projects.control.RepoReservation;
import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A TEST-SCOPE implementation of the {@link TechnicalProcessRegistry} port — the monorepo's
 * {@code domain.process} registry, vendored here verbatim minus its {@code WorkspaceChangePublisher}
 * hint (that channel is qits-workspaces').
 *
 * <p>It is test-scope on purpose: migration-plan.md §3.3 sends the technical-process framework to
 * qits-workspace-daemon, and SPLIT-CONTRACT §4 says a seam pointing there becomes a port with
 * <em>no implementation shipped</em>. Nothing in {@code src/main} references this class, so the
 * published jars carry no process implementation. What it buys is that the pull/push/sync suites
 * keep asserting the real streamed narration — segment ordering, diamond dedup, cycle termination,
 * per-segment settle verdicts, the {@code remote-auth} hint — rather than being deleted or reduced
 * to "it did not throw". Adapting them to a hand-rolled fake would have changed what they prove.
 *
 * <p>Original doc follows.
 *
 * <p>The in-memory registry of live processes, keyed by process id and by the workspace or
 * repository they run against. {@code begin} registers a process <em>before</em> the work starts;
 * the terminal {@code done} clears the active mapping and starts a short retention window
 * ({@code qits.process.done-ttl-ms}) during which a late (re)subscriber still gets the full replay
 * plus an immediate {@code done}. An idle backstop force-finishes a process that never converges.
 */
@ApplicationScoped
public class InMemoryProcessRegistry implements TechnicalProcessRegistry {

  /** How long a completed process stays subscribable (full replay + immediate done) before 404. */
  @ConfigProperty(name = "qits.process.done-ttl-ms", defaultValue = "60000")
  long doneTtlMillis;

  /**
   * Backstop: a process <em>idle</em> (no emitted frame) this long is force-finished so it can't
   * leak forever. Deliberately an idle window, not a total-lifetime cap: a provision's process now
   * spans the whole bootstrap chain, whose length is unbounded (N commands × the per-command
   * await), so a fixed cap would either cut a legitimately long-but-active chain mid-run or be far
   * too long to reap a genuinely stuck process (e.g. a ready pattern that never matches). Measured
   * against {@link TechnicalProcess#millisSinceLastActivity()}: an actively streaming chain keeps
   * resetting it, a stalled one trips it.
   */
  @ConfigProperty(name = "qits.process.max-idle-ms", defaultValue = "900000")
  long maxIdleMillis;

  private final Map<String, InMemoryTechnicalProcess> byId = new ConcurrentHashMap<>();
  private final Map<String, String> activeByWorkspace = new ConcurrentHashMap<>();
  private final Map<String, RepoActive> activeByRepository = new ConcurrentHashMap<>();

  /**
   * The live repository-scoped occupant plus its operation kind (e.g. {@code pull}/{@code sync}).
   * Exactly one of {@code processId} (a streamed {@link TechnicalProcess}) or {@code
   * reservationToken} (a {@link #reserveRepository lightweight lock}) is set.
   */
  private record RepoActive(String processId, String kind, String reservationToken) {
    static RepoActive process(String processId, String kind) {
      return new RepoActive(processId, kind, null);
    }

    static RepoActive reservation(String token, String kind) {
      return new RepoActive(null, kind, token);
    }

    boolean isReservation() {
      return reservationToken != null;
    }
  }

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "technical-process-registry");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /** The port's detached begin: a process keyed by nothing but its own id. */
  @Override
  public TechnicalProcess begin(String kind) {
    String id = UUID.randomUUID().toString();
    InMemoryTechnicalProcess process = new InMemoryTechnicalProcess(id, null, null, this::onDone);
    byId.put(id, process);
    scheduleIdleReaper(process);
    return process;
  }

  /** Register a new process for a workspace; the newest one is the workspace's active process. */
  public TechnicalProcess begin(String repoId, String workspaceId) {
    String id = UUID.randomUUID().toString();
    InMemoryTechnicalProcess process = new InMemoryTechnicalProcess(id, repoId, workspaceId, this::onDone);
    byId.put(id, process);
    activeByWorkspace.put(workspaceKey(repoId, workspaceId), id);
    scheduleIdleReaper(process);
    return process;
  }

  /**
   * Atomic, kind-aware single-flight for a repository-scoped process (null workspaceId) — for work
   * that operates on a repository as a whole rather than a single workspace (a streamed {@code
   * pull} or {@code sync}, passed as {@code kind}). Check-and-register run under the registry
   * monitor so two racing POSTs can't both register (the second would otherwise clobber the active
   * mapping and leave two walks contending on the bare origin's ref-locks):
   *
   * <ul>
   *   <li>A live process of the <em>same</em> kind → {@link RepoProcessLease.Reused} (the caller
   *       returns its id and starts no second walk; a reload / second tab reattaches to it via
   *       {@link #activeForRepository}).
   *   <li>A live process of a <em>different</em> kind → {@link RepoProcessLease.Conflict} (a pull
   *       and a sync can't share a walk — a pull would skip the push — nor run concurrently; the
   *       caller rejects).
   *   <li>Otherwise a fresh process is registered → {@link RepoProcessLease.Fresh}. It fires the
   *       {@code PROCESS} hint on the repository channel ({@code (repoId, null)} — see {@code
   *       RepositoryEventsController}) so the browser learns a pull became active without polling.
   * </ul>
   */
  public synchronized RepoProcessLease beginForRepository(String repoId, String kind) {
    RepoActive current = activeByRepository.get(repoId);
    if (current != null && isLive(current)) {
      // A reservation (the sign-in lock) is never a reusable process — any streamed op conflicts.
      if (current.isReservation() || !kind.equals(current.kind())) {
        return new RepoProcessLease.Conflict(current.kind());
      }
      return new RepoProcessLease.Reused(current.processId());
    }
    String id = UUID.randomUUID().toString();
    InMemoryTechnicalProcess process = new InMemoryTechnicalProcess(id, repoId, null, this::onDone);
    byId.put(id, process);
    activeByRepository.put(repoId, RepoActive.process(id, kind));
    scheduleIdleReaper(process);
    return new RepoProcessLease.Fresh(process);
  }

  /**
   * Reserve a repository for work that runs on its own channel (the interactive sign-in terminal),
   * not as a streamed process — see {@link RepoReservation}. Shares the {@code activeByRepository}
   * slot with {@link #beginForRepository} (so a reservation and a pull/sync/push are mutually
   * exclusive), but registers no {@link TechnicalProcess}: no idle reaper, and invisible to {@link
   * #activeForRepository} so it opens no empty process dialog. The caller must {@link
   * #releaseRepository} it with the returned token when the work ends.
   */
  public synchronized RepoReservation reserveRepository(String repoId, String kind) {
    RepoActive current = activeByRepository.get(repoId);
    if (current != null && isLive(current)) {
      return new RepoReservation.Conflict(current.kind());
    }
    String token = UUID.randomUUID().toString();
    activeByRepository.put(repoId, RepoActive.reservation(token, kind));
    return new RepoReservation.Acquired(token);
  }

  /**
   * Release a reservation, but only if this exact token still holds the repository (idempotent).
   */
  public synchronized void releaseRepository(String repoId, String token) {
    RepoActive current = activeByRepository.get(repoId);
    if (current != null && token.equals(current.reservationToken())) {
      activeByRepository.remove(repoId);
    }
  }

  /**
   * Whether {@code active} still holds the repository — a reservation always does, a process until
   * terminal.
   */
  private boolean isLive(RepoActive active) {
    if (active.isReservation()) {
      return true;
    }
    InMemoryTechnicalProcess existing = byId.get(active.processId());
    return existing != null && !existing.isTerminal();
  }

  /**
   * Force-finish the process once it has been idle for {@link #maxIdleMillis}; while it is still
   * producing frames, re-arm for the remaining idle window instead of cutting it off.
   */
  private void scheduleIdleReaper(InMemoryTechnicalProcess process) {
    scheduler.schedule(() -> reapIfIdle(process), maxIdleMillis, TimeUnit.MILLISECONDS);
  }

  private void reapIfIdle(InMemoryTechnicalProcess process) {
    if (process.isTerminal()) {
      return;
    }
    long idle = process.millisSinceLastActivity();
    if (idle < maxIdleMillis) {
      scheduler.schedule(() -> reapIfIdle(process), maxIdleMillis - idle, TimeUnit.MILLISECONDS);
    } else {
      process.forceFinish();
    }
  }

  /** The process for {@code id} — live or within its post-done retention window. */
  public Optional<TechnicalProcess> find(String id) {
    return Optional.ofNullable(id == null ? null : byId.get(id));
  }

  /** The id of the workspace's currently-running process, if any (cleared on done). */
  public Optional<String> activeFor(String repoId, String workspaceId) {
    return Optional.ofNullable(activeByWorkspace.get(workspaceKey(repoId, workspaceId)));
  }

  /**
   * The id of the repository's currently-running <em>streamed</em> process, if any (cleared on
   * done). A bare {@link #reserveRepository reservation} (the sign-in lock) is deliberately
   * invisible here — it has no frame stream to attach to, so surfacing it would open an empty
   * process dialog on reload.
   */
  public Optional<String> activeForRepository(String repoId) {
    RepoActive active = activeByRepository.get(repoId);
    if (active == null || active.isReservation()) {
      return Optional.empty();
    }
    return Optional.ofNullable(active.processId());
  }

  private void onDone(InMemoryTechnicalProcess process) {
    // Each scope clears its own active mapping and announces the change on its channel. Workspace-
    // scoped processes fire the per-workspace hint; repository-scoped ones (null workspaceId, from
    // beginForRepository) fire the repository hint (repoId, null).
    if (process.workspaceId() != null) {
      activeByWorkspace.remove(workspaceKey(process.repoId(), process.workspaceId()), process.id());
    } else if (process.repoId() != null) {
      clearRepositoryIfCurrent(process);
    }
    scheduler.schedule(() -> byId.remove(process.id()), doneTtlMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Clear the repository's active mapping only if it still points at <em>this</em> process — under
   * the same monitor {@link #beginForRepository} registers under, so a done racing a fresh begin
   * never removes the newer mapping (the {@link RepoActive} record is compared by id).
   */
  private synchronized void clearRepositoryIfCurrent(InMemoryTechnicalProcess process) {
    RepoActive current = activeByRepository.get(process.repoId());
    // Null-safe: the slot may now hold a reservation (null processId) that must not be cleared
    // here.
    if (current != null && process.id().equals(current.processId())) {
      activeByRepository.remove(process.repoId());
    }
  }

  private static String workspaceKey(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }
}
