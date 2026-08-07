package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.BackupOutcome;
import java.time.Instant;

/**
 * How a repository's last backup onto its forge twin went.
 *
 * <p>Absent (null on {@code RepositoryDto}) means <b>never attempted</b>, which is deliberately not
 * the same as failing: a repository nobody has pushed to yet has nothing to report, and showing it
 * as anything other than blank would be inventing a status.
 *
 * @param detail the short human line behind a non-success outcome; null after a success, because a
 *     stale reason beside a green outcome is worse than no reason at all
 */
public record LastBackupDto(BackupOutcome outcome, Instant at, String detail) {}
