package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One CI run's terminal verdict about a commit, as the repositories API answers it. {@code status}
 * is qits-ci's own word — {@code SUCCESS}, {@code FAILED}, {@code TIMED_OUT} or {@code
 * CONFIG_ERROR} today; the vocabulary is the publisher's and may grow. Runs still queued or running
 * do not appear: only terminal runs announce, so absence means "no verdict yet", never "no run".
 */
public record CommitBuildStatusDto(
    String runId, String status, String branch, boolean gating, Instant finishedAt) {}
