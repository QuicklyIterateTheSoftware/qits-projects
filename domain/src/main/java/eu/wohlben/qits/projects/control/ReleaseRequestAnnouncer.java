package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the rest of the platform that a release request's <b>content</b> changed — a new fold landed
 * on its backing branch, so there is a new sha to build, gate and eventually release.
 *
 * <p>A port, for the reason every other reach out of {@code domain} is one: the announcement leaves
 * over {@code qits-eventstream}'s bus, and {@code domain} does not know the event bus exists. The
 * one implementation is {@code service/…/bus/ReleaseRequestChangedAnnouncer}.
 *
 * <p><b>What it is for.</b> qits-ci is the motivating consumer: a release request's backing branch
 * is not a branch anybody pushed, so no {@code SCMPublishCommit} ever announces it — the git host's
 * merge primitive deliberately fires no post-receive. Without this event the fold would exist and
 * nothing would build it.
 *
 * <p><b>Only a real change is announced.</b> A fold that produced nothing new ({@code unchanged} at
 * the git host — every head already contained, same sha, no new commit) is not a change: announcing
 * it would ask for a build of a sha already built, and the triggers that fire on a set that did not
 * really move (a duplicate delivery, a pending tag leaving) are exactly the ones that produce it.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here: with no implementation a
 * request still merges and simply announces nothing, which is what {@code domain}'s own suite runs
 * as. Injected as an {@code Instance<T>} for that reason. <b>Nothing here may throw</b>, and nothing
 * here may be called inside a transaction the caller needs.
 */
public interface ReleaseRequestAnnouncer {

  /**
   * A release request's backing branch has a new tip.
   *
   * @param projectId the project the repository belongs to, or null where it has none
   * @param repoId the repository's storage id — the git host's key, and qits-ci's
   * @param repoName the repository's public name at the time of the change, or null
   * @param releaseRequestId the request's id, which is also the tail of its backing branch
   * @param backingBranch {@code release/<id>} — the branch the fold landed on
   * @param mergedSha the tip of the fold: what to build, gate and release
   * @param changedAt when the merge landed — the event's {@code occurredAt}, not the moment the
   *     announcement is made
   */
  void onReleaseRequestChanged(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt);
}
