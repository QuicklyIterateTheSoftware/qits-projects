package eu.wohlben.qits.projects.control;

/**
 * A branch this service released and <b>deleted on the git host</b>, told to the context that may
 * have a workspace standing on it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>It exists because of an <em>absence</em>. {@code ReleaseExecutor} deletes each consumed branch
 * through a qits-githost primitive that writes the ref in core and fires no event — the same
 * property that makes a release's own bookkeeping quiet — so nothing on the bus ever says that a
 * branch stopped existing, and nothing else in the platform is in a position to tell qits-workspaces
 * that the branch under one of its workspaces died. The workspace therefore stays ACTIVE for ever,
 * holding a container, a volume and a commissioned idp credential for a ref nobody can fetch, until
 * somebody notices and abandons it by hand. That was measured live on 2026-09-05: the storage-creep
 * wrapper workspace was still ACTIVE with a RUNNING container long after its branch had been
 * released. So the release says so, beside the deletion, and this is the seam it says it through.
 *
 * <h2>Best-effort, and that is a contract</h2>
 *
 * <p><b>An implementation must never throw.</b> Every failure — no address configured, no machine
 * credential, an unreachable or refusing qits-workspaces, a body that will not parse — is one
 * behaviour: say so once and return. The call happens <em>after</em> a release that has already
 * happened, and a resolution failure must never fail one, re-settle one or hold the release worker.
 * The caller keeps a belt round the loop anyway, the way {@code ReleaseRequests} does round the
 * executor: a throw is a port bug, not a release outcome.
 *
 * <p>It is also idempotent at the far side by construction — a branch with no workspace is the
 * ordinary answer, not an error, and it is the same answer a second call gets — so a lost call
 * costs the reap and nothing else.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present nothing is
 * asked and workspaces linger exactly as they did before this port existed: the release still
 * stamps, tags, deletes its branches and settles RELEASED, and a workspace on a released branch is
 * still there for somebody to abandon by hand. That is a loss of reaping, never of releasing.
 *
 * <h2>Why this is not a third verb on {@link WorkspaceLifecycle}</h2>
 *
 * <p>{@code WorkspaceLifecycle} is the obvious home and it is the wrong one, for a mechanical
 * reason rather than a taxonomic one: <b>it has no production adapter</b>. Nothing in {@code
 * service} implements it — the only implementation in this repository is the suite's recording
 * double — so an HTTP adapter added for this one verb would be forced to co-implement
 * {@code createMainWorkspace} and {@code releaseRepository} as well, and each of those is a live
 * flow: the first runs inside a clone and is a synchronous precondition of it, the second inside the
 * repository-delete transaction. Wiring a real client into either of them would light up behaviour
 * this change does not own and cannot test. A separate port keeps the blast radius exactly one call
 * on exactly one path.
 */
public interface ReleasedBranchWorkspaces {

  /**
   * The branch was released and is gone on the git host; resolve whatever workspace stood on it.
   *
   * <p>Never throws. Answers nothing, on purpose: the caller has already released and there is no
   * outcome here it could act on.
   *
   * @param repoId the <b>catalog</b> repository id — this service's row id, which is what
   *     qits-workspaces keys its {@code repository_id} by
   * @param branch the released branch, which no longer exists on the git host
   * @param version the release version, carried onto the far side's history entry
   * @param releasedSha the commit the release tag points at, carried onto the same entry
   */
  void branchReleased(String repoId, String branch, String version, String releasedSha);
}
