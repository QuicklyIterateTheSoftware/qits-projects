package eu.wohlben.qits.projects.control;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The workspaces context's registry, narrowed to the two facts the projects context actually reads
 * of a workspace: which branch it owns, and which branch it integrates back into.
 *
 * <p><strong>A port, not an implementation.</strong> {@code Workspace}, {@code workspace},
 * {@code workspace_event} and the prompt-draft tables belong to qits-workspaces, in a different
 * physical database (migration-plan.md §7), so a JPA relation or a foreign key from here is
 * impossible — this is the mirror image of qits-workspaces' {@code RepositoryLookup}, which reaches
 * the other way for exactly the same reason.
 *
 * <p>Injected as {@code Instance<WorkspaceLookup>} and <strong>optional</strong>, deliberately
 * unlike {@code RepositoryLookup}: a workspace has no meaning without a repository, but a
 * repository is perfectly meaningful without workspaces — an application that serves repositories,
 * the git host and epics without ever provisioning a container is a supported configuration. With
 * no implementation present:
 *
 * <ul>
 *   <li>{@code CommitService.listCommits} compares a workspace branch against the repository's main
 *       branch rather than the workspace's recorded parent — the same fallback it already takes for
 *       a branch that is not workspace-backed;
 *   <li>{@code RepositoryService.deleteBranch} loses the "branch has child workspaces" guard, which
 *       is a guard over rows this jar cannot see;
 *   <li>{@code CommitService.listIncomingCommits} 404s for a workspace id, as it does today for an
 *       unknown one.
 * </ul>
 */
public interface WorkspaceLookup {

  /** A branch's relationship to the workspace tree, as the branch list renders it. */
  record BranchSummary(String parent, Integer ahead, Integer behind) {}

  /**
   * The workspace facts this context reads.
   *
   * @param workspaceId the workspace's business id, unique per repository among ACTIVE rows
   * @param branch the branch the workspace owns, or null when it has none recorded
   * @param parent the branch it integrates back into, or null when it has no parent
   */
  record WorkspaceView(String workspaceId, String branch, String parent) {}

  /** The ACTIVE workspace with this business id under {@code repoId}, if any. */
  Optional<WorkspaceView> findActive(String repoId, String workspaceId);

  /** Every ACTIVE workspace under {@code repoId}. Empty is a valid answer, not an error. */
  List<WorkspaceView> findActiveByRepository(String repoId);

  /**
   * The host path of the workspace checkout that owns {@code branch}, when there is one. The pull
   * walk fast-forwards it alongside the bare origin so a live workspace is not left behind. Empty
   * means "pull into the bare origin only".
   */
  Optional<Path> workspacePathForBranch(String repoId, String branch);

  /** Where {@code branch} sits relative to the workspace tree, for the branch list. */
  BranchSummary summarize(String repoId, Path originPath, String branch, String mainBranch);

  /** Whether {@code branch} is fully merged and has no dependents, so cleanup is safe to offer. */
  boolean canCleanupBranch(String repoId, Path originPath, String branch, String mainBranch);
}
