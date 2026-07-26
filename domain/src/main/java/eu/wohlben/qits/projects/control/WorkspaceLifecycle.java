package eu.wohlben.qits.projects.control;

/**
 * The two things the projects context <em>asks the workspaces context to do</em>, as opposed to the
 * facts it reads through {@link WorkspaceLookup}.
 *
 * <p><strong>A port, not an implementation</strong>, and optional for the same reason (see
 * {@link WorkspaceLookup}): a deployment that serves repositories, the git host and epics without
 * ever provisioning a container is supported. With no implementation present a cloned repository
 * simply has no default workspace, and deleting a repository removes its bare origin, its rows and
 * its name aliases but leaves no containers or volumes to reap — because there were none.
 *
 * <p>Deliberately NOT modelled as a {@code WorkspaceChangePublisher} hint (SPLIT-CONTRACT §4's
 * preference): both verbs are synchronous preconditions of the call that makes them —
 * {@code cloneRepository} must not return before its main workspace exists, and
 * {@code deleteInternal} must not drop the repository row before the containers holding its volumes
 * are gone (docker refuses an in-use volume). A payload-free async hint cannot carry either
 * ordering.
 */
public interface WorkspaceLifecycle {

  /**
   * Create the default workspace checked out on {@code branch}, so a freshly cloned repository's
   * main branch is immediately workable and appears as a workspace-backed root in the branch tree.
   */
  void createMainWorkspace(String repoId, String branch);

  /**
   * Tear down everything this repository's workspaces hold outside the projects database: their
   * containers first, then their per-workspace volumes (docker refuses an in-use volume). Called
   * inside the repository-delete transaction, before the row goes. Best-effort by contract — a
   * failure here must not abort the delete.
   */
  void releaseRepository(String repoId);
}
