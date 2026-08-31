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
   * the arms that can only speak storage ids.
   */
  Outcome release(
      String repoId, String projectId, String repoName, String branch, String summary,
      String requester);

  /** What the door said: a version when it released, otherwise why it did not. */
  record Outcome(boolean released, String version, String detail) {

    public static Outcome released(String version) {
      return new Outcome(true, version, null);
    }

    public static Outcome refused(String detail) {
      return new Outcome(false, null, detail);
    }
  }
}
