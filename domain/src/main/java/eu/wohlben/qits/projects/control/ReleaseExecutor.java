package eu.wohlben.qits.projects.control;

/**
 * The arm that performs a release once its gates have passed — today qits-workspaces' release door,
 * called over HTTP by {@code service/…/releasehost}, later the system-role execution half of the
 * door split.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported — a deployment with no
 * workspaces address leaves every READY request standing with a detail that says why, which is a
 * visible refusal rather than a silent one. An implementation must not throw: every outcome is an
 * {@link Outcome}, and {@code released=false} carries the reason.
 */
public interface ReleaseExecutor {

  /**
   * Release {@code branch} of the repository the request names, acting as {@code requester}.
   * {@code projectId}/{@code repoName} are the public address pair; {@code repoId} rides along for
   * the arms that can only speak storage ids. {@code expectedSha} is the pin: the gates evaluated
   * that commit, and the door refuses ({@code HEAD_MOVED}) rather than landing a head that moved
   * past it.
   */
  Outcome release(
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String expectedSha,
      String summary,
      String requester);

  /**
   * What the door said: a version when it released, otherwise why it did not — and whether retrying
   * the same ask can possibly change the answer. Retryable is the moment failing (unreachable, a
   * 5xx, the door's own retry-me 409s); a refusal about the request itself — ALREADY_INTEGRATED, a
   * branch that is gone, a malformed ask — will answer the same forever, and the sweep must not
   * knock every 30 seconds to hear it again. A non-retryable FAILED request still revives on a
   * re-arm, which is the event that actually changes the ask.
   */
  record Outcome(boolean released, String version, String detail, boolean retryable) {

    public static Outcome released(String version) {
      return new Outcome(true, version, null, false);
    }

    /** A refusal about the request: final until the branch moves. */
    public static Outcome refused(String detail) {
      return new Outcome(false, null, detail, false);
    }

    /** A refusal about the moment: the sweep retries it. */
    public static Outcome refusedRetryable(String detail) {
      return new Outcome(false, null, detail, true);
    }
  }
}
