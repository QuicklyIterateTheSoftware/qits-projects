package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * The arm that performs a release once its gates have passed.
 *
 * <p><b>It used to be a door and it is a procedure now.</b> Until 2026-09-03 this port called
 * qits-workspaces' {@code /branches/execute-release}, which merged the branch into {@code main},
 * bumped, committed, pushed, tagged and promoted — and the release flow this epic builds does none
 * of that: a release is a <b>tag</b>, {@code main} is finalized after the deployment, and the
 * content being released is a fold that exists only on the git host. So the whole of what an
 * implementation does now is: stamp a version, rewrite the manifests at the fold, commit them onto
 * the backing branch, tag that commit, delete the branches the release consumed, and announce it.
 * {@code qits.projects.release-requests.workspaces-url} went with the door.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported — a deployment with no
 * executor leaves every READY request standing with a detail that says why, which is a visible
 * refusal rather than a silent one. An implementation <b>must not throw</b>: every outcome is an
 * {@link Outcome}, and {@code released=false} carries the reason.
 */
public interface ReleaseExecutor {

  /**
   * Everything one release is about. A record rather than a parameter list because the tag-only
   * flow needs the request's own identity and its source set, and an eight-argument signature that
   * grew two more would be the kind nobody can read a call site of.
   *
   * @param requestId the release request — its own id, and the tail of {@link #backingBranch}
   * @param repoId the repository's storage id, the git host's key
   * @param projectId the project the repository belongs to, or null
   * @param repoName the repository's registered name, or null. Rides onto {@code SCMRelease}, where
   *     it is the coordinate a committed CI selection can address.
   * @param backingBranch {@code release/<id>} — <b>what is released</b>: the fold, not any one
   *     participant
   * @param mergedSha the fold the gates evaluated. The manifests are read at this commit and the
   *     bump lands on top of it.
   * @param summary what the requester said this release is, for the commit and the tag message
   * @param requester who asked, or null
   * @param namedSources the request's named source branches, {@code main} included — deleted on
   *     success, <b>except</b> {@link #defaultBranch}, which is never deleted by anything
   * @param defaultBranch the repository's default branch, so the exclusion above is a fact rather
   *     than a guess at the string {@code "main"}
   */
  record Release(
      String requestId,
      String repoId,
      String projectId,
      String repoName,
      String backingBranch,
      String mergedSha,
      String summary,
      String requester,
      List<String> namedSources,
      String defaultBranch) {}

  /** Release the fold. Never throws; a failure is an {@link Outcome}. */
  Outcome release(Release release);

  /**
   * What happened: a version when it released, otherwise why it did not — and whether retrying the
   * same ask can possibly change the answer. Retryable is the moment failing (an unreachable git
   * host, a 5xx, a ref that moved under a slow caller); a refusal about the request itself (a
   * manifest that will not parse, a backing branch that is gone) will answer the same forever, and
   * the sweep must not knock every 30 seconds to hear it again. A non-retryable FAILED request still
   * revives on a re-arm, which is the event that actually changes the ask.
   */
  record Outcome(
      boolean released, String version, String releasedSha, String detail, boolean retryable) {

    /**
     * @param version the calver that was stamped, which is also the tag's name
     * @param releasedSha <b>what the tag points at</b> — the version-bump commit, not the fold the
     *     gates evaluated, because the bump lands on top of it. The two differ by exactly one commit
     *     whenever the repository renders a version at all, and {@code released_tag_pending_merge}
     *     records this one.
     */
    public static Outcome released(String version, String releasedSha) {
      return new Outcome(true, version, releasedSha, null, false);
    }

    /** A refusal about the request: final until the fold moves. */
    public static Outcome refused(String detail) {
      return new Outcome(false, null, null, detail, false);
    }

    /** A refusal about the moment: the sweep retries it. */
    public static Outcome refusedRetryable(String detail) {
      return new Outcome(false, null, null, detail, true);
    }
  }
}
