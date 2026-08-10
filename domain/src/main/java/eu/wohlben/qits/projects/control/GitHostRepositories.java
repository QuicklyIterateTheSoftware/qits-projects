package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * The git host's lifecycle verbs this context needs — does it hold a repository, and can one be
 * created — over the wire contract qits-githost's {@code GitHostRoutes} serves
 * (projects-volume-decoupling-plan.md §2.1, §2.3, §3.2).
 *
 * <p><b>Mandatory, unlike every other port in this repo's README table.</b> Every one of those is
 * injected as {@code Instance<T>} because absence is a supported, documented configuration; this one
 * is a plain {@code @Inject} because a repository this service creates has nowhere to exist without
 * it — the same reasoning qits-workspaces' {@code RepositoryLookup} javadoc gives for being the one
 * mandatory port there. An application that pulls this jar in with no implementation is
 * misconfigured and should fail at startup rather than 404 every repository create at runtime.
 *
 * <p>{@code ensure} and {@code find} are the only two verbs this context needs (§2.1): no delete
 * (⚖2 — deleting a repository here does not remove its git-host history), no enumerate (nothing
 * walks the host any more), no set-HEAD (creation takes the default branch and never changes it
 * afterwards).
 */
public interface GitHostRepositories {

  /** {@code GET /git/<repoId>}'s 200 body: the repository's id and current default branch. */
  record HostRepository(String repoId, String defaultBranch) {}

  /**
   * {@code PUT /git/<repoId>} — creates the repository with {@code defaultBranch} if the
   * host does not hold it yet. <b>Idempotent</b>: a repeat call against an id that already exists
   * succeeds as a no-op, which is what makes create-then-publish safely re-runnable after a push
   * that failed partway — there is no delete verb to unwind with, and none is needed (§2.2).
   *
   * @return {@code true} when this call created the repository (the host answered 201), {@code
   *     false} when one already existed (200)
   * @throws GitHostException the host rejected the request, answered unexpectedly, or was
   *     unreachable
   */
  boolean ensure(String repoId, String defaultBranch);

  /**
   * {@code GET /git/<repoId>} — present means the host holds this repository, which is
   * what {@code adoptExistingOrigin} asks instead of a {@code Files.isDirectory} on the old shared
   * volume.
   *
   * @throws GitHostException the host answered with a status other than 200 or 404, or was
   *     unreachable
   */
  Optional<HostRepository> find(String repoId);
}
