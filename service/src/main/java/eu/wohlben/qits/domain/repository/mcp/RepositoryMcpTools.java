package eu.wohlben.qits.domain.repository.mcp;

import eu.wohlben.qits.domain.featureflow.control.ActionConfigurationService;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.ActionRunService;
import eu.wohlben.qits.domain.repository.control.CommitService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService.MergeResult;
import eu.wohlben.qits.domain.repository.dto.BranchDto;
import eu.wohlben.qits.domain.repository.dto.CommitChangesDto;
import eu.wohlben.qits.domain.repository.dto.CommitFileDiffDto;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
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
 * diffs; manipulate workspaces (branch off, integrate, clean up, merge a parent in); and
 * <em>run</em> a non-interactive action in a workspace via {@code runAction}. That last tool
 * <em>consumes</em> an action from the global library but does not configure one — defining and
 * editing actions is the job of the separate "actions" server (see {@link
 * eu.wohlben.qits.domain.featureflow.mcp.ActionConfigurationMcpTools}). The split is intentional: a
 * session here is for getting work done in a checkout, not for changing what actions exist, so
 * {@code runAction} and {@code listActions} here only ever read and use the library. Daemons are
 * the exception that proves the rule: a daemon is repository-owned configuration (there is no
 * global daemon library), so defining, editing, starting and stopping daemons all live on this
 * server too — in {@link eu.wohlben.qits.domain.daemon.mcp.DaemonMcpTools}.
 *
 * <p>Each session is scoped to a single project via {@link ProjectScope} (the {@code
 * X-QITS-Project} header), and may be further narrowed to one repository within it (the optional
 * {@code X-QITS-Repository} header). Tools take a {@code repoId} but never a project id, and {@link
 * #requireRepoInProject} rejects any repository that does not belong to the scoped project — or,
 * when the session is narrowed, any repository other than the scoped one — so the model cannot
 * reach across project boundaries or out of its repository.
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

  @Inject ProjectScopeGuard scopeGuard;

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject CommitService commitService;

  @Inject WorkspaceService workspaceService;

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
    var scopedRepo = scope.repositoryId();
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .filter(r -> scopedRepo.isEmpty() || scopedRepo.get().equals(r.id))
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
          "List the workspaces of a repository (a workspace owns a feature branch forked from a"
              + " parent), with ahead/behind counts and whether merging its parent in would"
              + " conflict. Needed to integrate or to merge the parent into a workspace.")
  @Transactional
  public List<WorkspaceDto> listWorkspaces(
      @ToolArg(description = "id of a repository in this project") String repoId) {
    requireRepoInProject(repoId);
    return workspaceService.listWorkspaces(repoId);
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

  /** Result of creating a workspace. */
  public record CreatedWorkspace(String workspaceId, String parent) {}

  @McpServer("repository")
  @Tool(
      description =
          "Branch off a new workspace: create a workspace that owns a fresh branch forked from"
              + " 'parent'. The workspaceId must match [A-Za-z0-9_-]{1,64}. Omit 'parent' to fork"
              + " from master, and 'branch' to name the new branch after the workspace.")
  public CreatedWorkspace createWorkspace(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "slug for the new workspace, [A-Za-z0-9_-]{1,64}") String workspaceId,
      @ToolArg(required = false, description = "branch to fork from (default: master)")
          String parent,
      @ToolArg(required = false, description = "name for the new branch (default: the workspaceId)")
          String branch) {
    requireRepoInProject(repoId);
    var wt = workspaceService.createWorkspace(repoId, workspaceId, parent, branch);
    return new CreatedWorkspace(wt.workspaceId, wt.parent);
  }

  /** Result of a cleanup. */
  public record CleanupResult(String branch, boolean cleanedUp) {}

  @McpServer("repository")
  @Tool(
      description =
          "Clean up a branch: remove it (and its workspace, if any) once it is fully merged with no"
              + " dependent workspaces and a clean working tree. Refuses (error) when the branch"
              + " still has unmerged commits or dependents, so it never loses work.")
  public CleanupResult cleanupBranch(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "branch to clean up") String branch) {
    requireRepoInProject(repoId);
    workspaceService.cleanupBranch(repoId, branch);
    return new CleanupResult(branch, true);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Integrate a branch into a target branch (default: the repository's main branch) by"
              + " merging it. When the integration is clean and the source is then fully merged"
              + " with no dependents, the source branch/workspace is cleaned up automatically"
              + " (reported via cleanedUp). hasConflicts=true means the merge hit conflicts.")
  public MergeResult integrateBranch(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "branch to integrate") String source,
      @ToolArg(required = false, description = "branch to integrate into (default: main branch)")
          String target) {
    requireRepoInProject(repoId);
    return workspaceService.mergeBranch(repoId, source, target);
  }

  /** Result of merging a workspace's parent into it. */
  public record MergeParentResult(String workspaceId, String output) {}

  @McpServer("repository")
  @Tool(
      description =
          "Merge a workspace's parent branch (e.g. master) into the workspace, so a branch that has"
              + " fallen behind catches up. Creates a merge commit. If it would conflict the merge"
              + " is aborted and an error is returned, leaving the workspace untouched.")
  public MergeParentResult mergeParentIntoWorkspace(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "id of the workspace to update") String workspaceId) {
    requireRepoInProject(repoId);
    String output = workspaceService.updateWorkspaceFromParent(repoId, workspaceId);
    return new MergeParentResult(workspaceId, output);
  }

  // --- Run actions ----------------------------------------------------------

  /** A non-interactive action the model can run, trimmed to what it needs to pick one. */
  public record RunnableAction(String id, String name, String description) {}

  @McpServer("repository")
  @Tool(
      description =
          "List the non-interactive actions — one-off commands such as a test or build — that"
              + " runAction can run in a workspace. Interactive actions (a shell, Claude Code) are"
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
          "Run a non-interactive action (a one-off command, e.g. 'mvn test') in a workspace checkout"
              + " and return its combined stdout/stderr and exit code. The action's script runs in a"
              + " login shell in the workspace directory with the action's environment. Refuses"
              + " interactive actions. Get actionIds from listActions and workspaceIds from"
              + " listWorkspaces.")
  public ActionRunService.RunResult runAction(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "id of the workspace to run in") String workspaceId,
      @ToolArg(description = "id of a non-interactive action (see listActions)") String actionId) {
    requireRepoInProject(repoId);
    return actionRunService.run(repoId, workspaceId, actionId);
  }

  // --- Scoping --------------------------------------------------------------

  /** See {@link ProjectScopeGuard#requireRepoInProject} — shared with the daemon tools. */
  private Repository requireRepoInProject(String repoId) {
    return scopeGuard.requireRepoInProject(repoId);
  }
}
