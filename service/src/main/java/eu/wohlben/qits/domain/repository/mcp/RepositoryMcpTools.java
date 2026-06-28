package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.featureflow.control.ActionConfigurationService;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.ActionRunService;
import eu.wohlben.qits.domain.repository.control.CommitService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorktreeService;
import eu.wohlben.qits.domain.repository.control.WorktreeService.MergeResult;
import eu.wohlben.qits.domain.repository.dto.BranchDto;
import eu.wohlben.qits.domain.repository.dto.CommitChangesDto;
import eu.wohlben.qits.domain.repository.dto.CommitFileDiffDto;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.dto.WorktreeDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * The "repository" MCP server — the working surface for a project's git repositories, exposed to an
 * LLM. Every tool is bound to the named {@code repository} MCP server (mounted at {@code
 * /mcp/repository}); nothing from other domain areas (projects, feature flows) is exposed here, so
 * a client connected to this endpoint only ever sees repository tools and stays on task.
 *
 * <p><strong>Use case: working inside a repository.</strong> Inspect its branches, commits and
 * diffs; manipulate worktrees (branch off, integrate, clean up, merge a parent in); and
 * <em>run</em> a non-interactive action in a worktree via {@code runAction}. That last tool
 * <em>consumes</em> an action from the global library but does not configure one — defining and
 * editing actions is the job of the separate "actions" server (see {@link
 * eu.wohlben.qits.domain.featureflow.mcp.ActionConfigurationMcpTools}). The split is intentional: a
 * session here is for getting work done in a checkout, not for changing what actions exist, so
 * {@code runAction} and {@code listActions} here only ever read and use the library.
 *
 * <p>Each session is scoped to a single project via {@link ProjectScope} (the {@code
 * X-QITS-Project} header). Tools take a {@code repoId} but never a project id, and {@link
 * #requireRepoInProject} rejects any repository that does not belong to the scoped project — so the
 * model cannot reach across project boundaries.
 *
 * <p>{@link WrapBusinessError} turns any exception a tool throws — the scoping guards here and the
 * domain {@code NotFoundException}/{@code BadRequestException}s from the services — into a tool
 * result with {@code isError=true} carrying the message, so the model sees a readable failure
 * instead of a hard JSON-RPC protocol error.
 */
@ApplicationScoped
@WrapBusinessError
public class RepositoryMcpTools {

  @Inject ProjectScope scope;

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject CommitService commitService;

  @Inject WorktreeService worktreeService;

  @Inject ActionConfigurationService actionConfigurationService;

  @Inject ActionRunService actionRunService;

  // --- Context (read) -------------------------------------------------------

  /** A repository visible to this session, trimmed to what the model needs to pick one. */
  public record RepositorySummary(String id, String url, String archetype, String mainBranch) {}

  @McpServer("repository")
  @Tool(
      description =
          "List the git repositories belonging to the project this session is scoped to. Start here"
              + " to obtain a repoId for the other tools.")
  @Transactional
  public List<RepositorySummary> listRepositories() {
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .map(
            r ->
                new RepositorySummary(
                    r.id, r.url, r.archetype == null ? null : r.archetype.name(), r.mainBranch))
        .toList();
  }

  @McpServer("repository")
  @Tool(
      description =
          "List the branches of a repository. Each branch reports its parent, how far it is"
              + " ahead/behind that parent, and whether it can be safely cleaned up (fully merged,"
              + " no dependents).")
  @Transactional
  public List<BranchDto> listBranches(
      @ToolArg(description = "id of a repository in this project") String repoId) {
    requireRepoInProject(repoId);
    return repositoryService.listBranchesWithCleanup(repoId);
  }

  @McpServer("repository")
  @Tool(
      description =
          "List the worktrees of a repository (a worktree owns a feature branch forked from a"
              + " parent), with ahead/behind counts and whether merging its parent in would"
              + " conflict. Needed to integrate or to merge the parent into a worktree.")
  @Transactional
  public List<WorktreeDto> listWorktrees(
      @ToolArg(description = "id of a repository in this project") String repoId) {
    requireRepoInProject(repoId);
    return worktreeService.listWorktrees(repoId);
  }

  @McpServer("repository")
  @Tool(
      description =
          "List the commits unique to a branch (the commits on it that are not on its parent),"
              + " newest first, each with the files it changed.")
  @Transactional
  public CommitLogDto listCommits(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "branch name to read the log of") String branch) {
    requireRepoInProject(repoId);
    return commitService.listCommits(repoId, branch);
  }

  @McpServer("repository")
  @Tool(
      description =
          "List the files a commit changed relative to its diff base (the explicit parent, or the"
              + " commit's own first parent when omitted). This is the commit-detail view; use"
              + " getCommitFileDiff for the contents of one file.")
  @Transactional
  public CommitChangesDto listCommitChanges(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "full or short commit hash") String commitHash,
      @ToolArg(required = false, description = "diff base; omit to use the commit's first parent")
          String parent) {
    requireRepoInProject(repoId);
    return commitService.listChanges(repoId, commitHash, parent);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Get the unified diff of a single file within a commit, relative to the same base as"
              + " listCommitChanges. The diff is empty for a binary file or a pure rename.")
  @Transactional
  public CommitFileDiffDto getCommitFileDiff(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "full or short commit hash") String commitHash,
      @ToolArg(description = "path of the file within the repository") String path,
      @ToolArg(required = false, description = "diff base; omit to use the commit's first parent")
          String parent) {
    requireRepoInProject(repoId);
    return commitService.getFileDiff(repoId, commitHash, parent, path);
  }

  // --- Actions (write) ------------------------------------------------------

  /** Result of creating a worktree. */
  public record CreatedWorktree(String worktreeId, String parent) {}

  @McpServer("repository")
  @Tool(
      description =
          "Branch off a new worktree: create a worktree that owns a fresh branch forked from"
              + " 'parent'. The worktreeId must match [A-Za-z0-9_-]{1,64}. Omit 'parent' to fork"
              + " from master, and 'branch' to name the new branch after the worktree.")
  public CreatedWorktree createWorktree(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "slug for the new worktree, [A-Za-z0-9_-]{1,64}") String worktreeId,
      @ToolArg(required = false, description = "branch to fork from (default: master)")
          String parent,
      @ToolArg(required = false, description = "name for the new branch (default: the worktreeId)")
          String branch) {
    requireRepoInProject(repoId);
    var wt = worktreeService.createWorktree(repoId, worktreeId, parent, branch);
    return new CreatedWorktree(wt.worktreeId, wt.parent);
  }

  /** Result of a cleanup. */
  public record CleanupResult(String branch, boolean cleanedUp) {}

  @McpServer("repository")
  @Tool(
      description =
          "Clean up a branch: remove it (and its worktree, if any) once it is fully merged with no"
              + " dependent worktrees and a clean working tree. Refuses (error) when the branch"
              + " still has unmerged commits or dependents, so it never loses work.")
  public CleanupResult cleanupBranch(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "branch to clean up") String branch) {
    requireRepoInProject(repoId);
    worktreeService.cleanupBranch(repoId, branch);
    return new CleanupResult(branch, true);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Integrate a branch into a target branch (default: the repository's main branch) by"
              + " merging it. When the integration is clean and the source is then fully merged"
              + " with no dependents, the source branch/worktree is cleaned up automatically"
              + " (reported via cleanedUp). hasConflicts=true means the merge hit conflicts.")
  public MergeResult integrateBranch(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "branch to integrate") String source,
      @ToolArg(required = false, description = "branch to integrate into (default: main branch)")
          String target) {
    requireRepoInProject(repoId);
    return worktreeService.mergeBranch(repoId, source, target);
  }

  /** Result of merging a worktree's parent into it. */
  public record MergeParentResult(String worktreeId, String output) {}

  @McpServer("repository")
  @Tool(
      description =
          "Merge a worktree's parent branch (e.g. master) into the worktree, so a branch that has"
              + " fallen behind catches up. Creates a merge commit. If it would conflict the merge"
              + " is aborted and an error is returned, leaving the worktree untouched.")
  public MergeParentResult mergeParentIntoWorktree(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "id of the worktree to update") String worktreeId) {
    requireRepoInProject(repoId);
    String output = worktreeService.updateWorktreeFromParent(repoId, worktreeId);
    return new MergeParentResult(worktreeId, output);
  }

  // --- Run actions ----------------------------------------------------------

  /** A non-interactive action the model can run, trimmed to what it needs to pick one. */
  public record RunnableAction(String id, String name, String description) {}

  @McpServer("repository")
  @Tool(
      description =
          "List the non-interactive actions — one-off commands such as a test or build — that"
              + " runAction can run in a worktree. Interactive actions (a shell, Claude Code) are"
              + " excluded: those are for the human terminal, not for the model to run.")
  @Transactional
  public List<RunnableAction> listActions() {
    return actionConfigurationService.list().stream()
        .filter(a -> !a.interactive)
        .map(a -> new RunnableAction(a.id, a.name, a.description))
        .toList();
  }

  @McpServer("repository")
  @Tool(
      description =
          "Run a non-interactive action (a one-off command, e.g. 'mvn test') in a worktree checkout"
              + " and return its combined stdout/stderr and exit code. The action's script runs in a"
              + " login shell in the worktree directory with the action's environment. Refuses"
              + " interactive actions. Get actionIds from listActions and worktreeIds from"
              + " listWorktrees.")
  public ActionRunService.RunResult runAction(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "id of the worktree to run in") String worktreeId,
      @ToolArg(description = "id of a non-interactive action (see listActions)") String actionId) {
    requireRepoInProject(repoId);
    return actionRunService.run(repoId, worktreeId, actionId);
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * Ensures {@code repoId} names a repository inside the project this session is scoped to, so no
   * tool can operate on a repository from another project. Throws {@link NotFoundException}
   * otherwise (also covering a non-existent repository).
   */
  private Repository requireRepoInProject(String repoId) {
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .filter(r -> r.id.equals(repoId))
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException("Repository not found in this project: " + repoId));
  }
}
