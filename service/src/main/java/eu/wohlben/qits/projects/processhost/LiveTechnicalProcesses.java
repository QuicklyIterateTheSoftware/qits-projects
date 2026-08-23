package eu.wohlben.qits.projects.processhost;

import eu.wohlben.qits.projects.control.RepoProcessLease;
import eu.wohlben.qits.projects.control.RepoReservation;
import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-memory implementation of the domain's {@link TechnicalProcessRegistry} port — the piece
 * the port's own javadoc says an assembling application supplies when it wants the narration. Until
 * the refinement subsystem there was no subscriber, so the port stood unimplemented and every
 * repository operation ran unnarrated; the refinement ensure is the subscriber that makes it worth
 * standing up, and the repository-scoped half lights up with it.
 *
 * <p><b>Everything here is this process's memory.</b> A process id is subscribable while the
 * operation runs and for {@code qits.projects.process.done-ttl-ms} after it settles; an id nobody
 * would ever hear from again answers empty, which the SSE controller turns into the 404 the
 * frontend reads as "expired". A process that goes quiet without settling is force-finished after
 * {@code qits.projects.process.max-idle-ms} rather than living forever.
 */
@ApplicationScoped
@DefaultBean
public class LiveTechnicalProcesses implements TechnicalProcessRegistry {

  private static final Logger LOG = Logger.getLogger(LiveTechnicalProcesses.class);

  @ConfigProperty(name = "qits.projects.process.done-ttl-ms", defaultValue = "60000")
  long doneTtlMs;

  @ConfigProperty(name = "qits.projects.process.max-idle-ms", defaultValue = "900000")
  long maxIdleMs;

  private final Map<String, LiveTechnicalProcess> byId = new ConcurrentHashMap<>();

  /** What holds each repository right now: a narrated process or a reservation. */
  private record Hold(String kind, String processId, String reservationToken) {}

  private final Map<String, Hold> repoHolds = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "technical-process-reaper");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Register a process scoped to nothing but its own id — the refinement ensure's shape. The
   * caller owns driving it to a terminal frame; the idle reaper is the backstop.
   */
  @Override
  public TechnicalProcess begin(String kind) {
    String id = UUID.randomUUID().toString();
    LiveTechnicalProcess process = new LiveTechnicalProcess(id, () -> scheduleEviction(id));
    byId.put(id, process);
    scheduleIdleReap(id);
    return process;
  }

  @Override
  public RepoProcessLease beginForRepository(String repoId, String kind) {
    synchronized (repoHolds) {
      Hold hold = repoHolds.get(repoId);
      if (hold != null) {
        if (hold.kind().equals(kind) && hold.processId() != null) {
          return new RepoProcessLease.Reused(hold.processId());
        }
        return new RepoProcessLease.Conflict(hold.kind());
      }
      String id = UUID.randomUUID().toString();
      LiveTechnicalProcess process =
          new LiveTechnicalProcess(
              id,
              () -> {
                releaseHoldOf(repoId, id);
                scheduleEviction(id);
              });
      byId.put(id, process);
      repoHolds.put(repoId, new Hold(kind, id, null));
      scheduleIdleReap(id);
      return new RepoProcessLease.Fresh(process);
    }
  }

  @Override
  public RepoReservation reserveRepository(String repoId, String kind) {
    synchronized (repoHolds) {
      Hold hold = repoHolds.get(repoId);
      if (hold != null) {
        return new RepoReservation.Conflict(hold.kind());
      }
      String token = UUID.randomUUID().toString();
      repoHolds.put(repoId, new Hold(kind, null, token));
      return new RepoReservation.Acquired(token);
    }
  }

  @Override
  public void releaseRepository(String repoId, String token) {
    synchronized (repoHolds) {
      Hold hold = repoHolds.get(repoId);
      if (hold != null && token != null && token.equals(hold.reservationToken())) {
        repoHolds.remove(repoId);
      }
    }
  }

  @Override
  public Optional<TechnicalProcess> find(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public Optional<String> activeForRepository(String repoId) {
    synchronized (repoHolds) {
      Hold hold = repoHolds.get(repoId);
      return hold == null ? Optional.empty() : Optional.ofNullable(hold.processId());
    }
  }

  private void releaseHoldOf(String repoId, String processId) {
    synchronized (repoHolds) {
      Hold hold = repoHolds.get(repoId);
      if (hold != null && processId.equals(hold.processId())) {
        repoHolds.remove(repoId);
      }
    }
  }

  private void scheduleEviction(String id) {
    scheduler.schedule(() -> byId.remove(id), doneTtlMs, TimeUnit.MILLISECONDS);
  }

  private void scheduleIdleReap(String id) {
    scheduler.schedule(
        () -> {
          LiveTechnicalProcess process = byId.get(id);
          if (process != null && !process.isTerminal()) {
            LOG.warnf("technical process %s never settled — force-finishing it", id);
            process.forceFinish();
          }
        },
        maxIdleMs,
        TimeUnit.MILLISECONDS);
  }
}
