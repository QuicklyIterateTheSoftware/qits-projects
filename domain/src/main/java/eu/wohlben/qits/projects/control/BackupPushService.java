package eu.wohlben.qits.projects.control;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Keeps every repository's forge twin in step with the git host, without anybody having to remember
 * to push.
 *
 * <p>Two triggers, and they are different jobs. The git host's {@code post-receive} calls {@link
 * #onPush} the moment a push is accepted, which is what makes the twin current within seconds of
 * real work. The scheduled sweep calls {@link #backupAll} on an interval, which is what makes it
 * <em>eventually</em> current after a missed event, an unreachable forge or an expired credential —
 * the thing an event-driven design alone quietly does not give you.
 *
 * <p><b>Debounced and serialized per repository</b>, because one {@code git push} is many events: a
 * push of three branches fires three post-receives, and a naive listener would run three backups of
 * the same repository, concurrently, against the same mirror. A repository already scheduled absorbs
 * further events into the run it is waiting for, and a per-repository lock means the sweep and an
 * event-driven run can never overlap.
 *
 * <p><b>Nothing here throws.</b> It is called from a fire-and-forget HTTP intake and from a
 * scheduler; there is no caller left to hand a failure to, and a backup that could fail a push would
 * be worse than no backup at all. Failures are logged — an auth wall by name, through the same
 * classifier the sign-in terminal keys off — and the drift they leave is exactly what {@code
 * syncStatus} already reports on the repository's own screen.
 */
@ApplicationScoped
public class BackupPushService {

  private static final Logger LOG = Logger.getLogger(BackupPushService.class);

  @Inject RepositoryService repositoryService;

  /**
   * How long a scheduled backup waits before it runs, absorbing the rest of the push's events.
   *
   * <p>Long enough to collapse one {@code git push}'s worth of post-receives (they arrive together),
   * short enough that the twin is current while somebody is still looking at the screen. Zero is
   * legal and is what the tests use.
   */
  @ConfigProperty(name = "qits.projects.backup.debounce-ms", defaultValue = "2000")
  long debounceMillis;

  /** The kill switch, honoured by both triggers so turning it off really does stop everything. */
  @ConfigProperty(name = "qits.projects.backup.enabled", defaultValue = "true")
  boolean enabled;

  /** One entry per repository that has been asked for, holding its debounce flag and its lock. */
  private final Map<String, RepoBackup> perRepo = new ConcurrentHashMap<>();

  /**
   * How many backup runs {@code repoId} has finished, successfully or not.
   *
   * <p>The one countable fact about a service that answers nobody: every trigger is fire-and-forget
   * and every failure is swallowed, so without this there is no way to tell "the debounce collapsed
   * five events into one run" from "nothing ran at all". Counted <b>per repository</b>, which is the
   * only scope the number means anything in — a global tally would move under one repository because
   * another one was being backed up.
   */
  public long completedRuns(String repoId) {
    RepoBackup state = perRepo.get(repoId);
    return state == null ? 0 : state.completed.get();
  }

  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "repository-backup");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** A repository's debounce flag and the lock that keeps its runs one at a time. */
  private static final class RepoBackup {
    final AtomicBoolean scheduled = new AtomicBoolean();
    final ReentrantLock running = new ReentrantLock();
    final java.util.concurrent.atomic.AtomicLong completed =
        new java.util.concurrent.atomic.AtomicLong();
  }

  /**
   * The git host accepted a push to {@code repoId}: schedule a backup, absorbing this event into a
   * run already waiting for one. Returns immediately and always.
   */
  public void onPush(String repoId) {
    if (!enabled || repoId == null || repoId.isBlank()) {
      return;
    }
    RepoBackup state = perRepo.computeIfAbsent(repoId, id -> new RepoBackup());
    if (!state.scheduled.compareAndSet(false, true)) {
      LOG.debugf("Backup of %s is already scheduled — folding this push into it.", repoId);
      return;
    }
    executor.submit(
        () -> {
          try {
            if (debounceMillis > 0) {
              Thread.sleep(debounceMillis);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.scheduled.set(false);
            return;
          }
          // Cleared BEFORE the push, not after: an event that arrives while this run is in flight
          // describes a commit this run may already have missed, so it has to schedule its own.
          state.scheduled.set(false);
          backupQuietly(repoId, state);
        });
  }

  /**
   * Backs up every repository that has a twin — the sweep's body. Synchronous and sequential: it is
   * a safety net running on an interval, not a race to finish, and one repository's forge being slow
   * is not a reason to open a connection to every other one at once.
   */
  public void backupAll() {
    if (!enabled) {
      return;
    }
    for (String repoId : repositoryService.repositoryIdsWithBackupTwin()) {
      backupQuietly(repoId, perRepo.computeIfAbsent(repoId, id -> new RepoBackup()));
    }
  }

  /**
   * One repository's backup, under its lock, with every failure absorbed. Package-private so the
   * suite can drive a single run without waiting on a debounce.
   */
  void backupQuietly(String repoId, RepoBackup state) {
    state.running.lock();
    try {
      String output = repositoryService.backupToTwin(repoId);
      LOG.debugf("Backed up %s: %s", repoId, output);
    } catch (RuntimeException e) {
      String message = e.getMessage();
      if (GitRemoteAuth.isAuthFailure(message)) {
        LOG.warnf(
            "Backup of %s was refused for want of credentials — sign in to its remote to restore"
                + " it: %s",
            repoId, message);
      } else {
        LOG.warnf("Backup of %s failed: %s", repoId, message);
      }
    } finally {
      state.completed.incrementAndGet();
      state.running.unlock();
    }
  }

  /** {@link #backupQuietly} for a repository the caller names — the suite's and the sweep's seam. */
  public void backupNow(String repoId) {
    backupQuietly(repoId, perRepo.computeIfAbsent(repoId, id -> new RepoBackup()));
  }
}
