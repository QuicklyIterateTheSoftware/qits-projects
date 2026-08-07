package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.BackupPushService;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The safety net under {@code BackupPushService}'s event trigger: every repository with a forge twin,
 * backed up on an interval whether or not anybody pushed.
 *
 * <p>It exists because the event path has three ways to leave a repository behind and none of them
 * announces itself — a post-receive lost while this service was restarting, a forge that was
 * unreachable for the minute the backup ran, a credential that expired between pushes. Each leaves a
 * twin quietly stale, and "quietly" is the part a periodic re-assert fixes. It is deliberately
 * modest: hourly by default, sequential, and every failure already swallowed by the service it
 * calls.
 *
 * <p>Packaged runs only, the same gate {@code StartupSelfSeed} carries and for the same reason: a
 * test suite or a {@code quarkus:dev} session must not start pushing to real forges in the
 * background. The suite drives {@code BackupPushService} directly instead.
 */
@ApplicationScoped
public class ScheduledBackupSweep {

  private static final Logger LOG = Logger.getLogger(ScheduledBackupSweep.class);

  @Inject BackupPushService backupPushService;

  /**
   * {@code qits.projects.backup.interval} — how often the sweep runs. The kill switch is {@code
   * qits.projects.backup.enabled}, read by {@link BackupPushService} itself so that turning it off
   * stops the event trigger too rather than only this one.
   */
  @Scheduled(
      every = "${qits.projects.backup.interval:1h}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweep() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      backupPushService.backupAll();
    } catch (RuntimeException e) {
      // backupAll swallows per-repository failures, so reaching here means something structural.
      // Still not fatal: the next sweep tries again.
      LOG.error("The scheduled backup sweep failed — retried on the next interval.", e);
    }
  }
}
