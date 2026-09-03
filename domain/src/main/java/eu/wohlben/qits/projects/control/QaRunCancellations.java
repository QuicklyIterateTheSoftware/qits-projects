package eu.wohlben.qits.projects.control;

/**
 * Cancels the QA runs of a release request whose fold has been superseded — qits-ci's
 * run-cancellation API, seen from this side of the boundary.
 *
 * <h2>Why it exists</h2>
 *
 * A release request's gate is correlated by sha: only a verdict naming the request's <b>current</b>
 * {@code mergedSha} can flip it, so a run still grinding away on a fold that has since been
 * superseded settles nothing and its verdict is ignored on arrival. The gate is therefore already
 * correct without this call. What this saves is a build agent: a busy branch re-folds several times
 * in a minute and every re-fold starts a fresh run, so without a cancellation the queue fills with
 * runs whose answers are known to be worthless before they finish.
 *
 * <p>That is exactly why it is <b>best effort and never fatal</b>. It buys throughput, not
 * correctness, so an unreachable qits-ci, a refusal, an unconfigured address and a service that has
 * never heard of the route are all one answer here: log it and carry on. <b>An implementation must
 * not throw</b>, and a caller must not make a fold conditional on it.
 *
 * <h2>Scoped to one request, always</h2>
 *
 * The far side is told the repository <b>and</b> the release request, and it cancels the runs of
 * that request alone. A sibling request of the same repository is folding its own sources onto its
 * own backing branch and its runs are none of this request's business — cancelling by repository
 * would take a neighbour's green build away seconds before it settled them. The correlation qits-ci
 * has to key on is the one it was given when the run was asked for: {@code ReleaseRequestChanged}
 * carries {@code releaseRequestId} beside the {@code mergedSha} it builds.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported — with no implementation
 * a superseded run simply runs to the end and its verdict is ignored, which is the behaviour the
 * gate had before this existed.
 */
public interface QaRunCancellations {

  /**
   * Cancel every in-flight QA run of one release request.
   *
   * @param repoId the repository's storage id — the git host's key, and qits-ci's
   * @param releaseRequestId the request whose runs are stale. <b>Never null and never widened to
   *     "everything in this repository"</b>: see the class javadoc.
   */
  void cancelRunsOf(String repoId, String releaseRequestId);
}
