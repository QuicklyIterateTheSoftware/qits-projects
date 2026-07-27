package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.CommitService;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.BranchDto;
import eu.wohlben.qits.projects.dto.CommitChangesDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.CommitLogDto;
import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.dto.SyncStatusDto;
import eu.wohlben.qits.projects.mapper.RepositoryMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

  @Inject RepositoryService repositoryService;

  @Inject CommitService commitService;

  @Inject RepositoryMapper repositoryMapper;

  /** SEAM: the technical-process framework is an optional port here (see the interface). */
  @Inject Instance<TechnicalProcessRegistry> technicalProcesses;

  public static record GetRepositoryRequest() {
    public record Response(RepositoryDto repository) {}
  }

  @GET
  @Path("/{repoId}")
  public GetRepositoryRequest.Response get(@PathParam("repoId") String repoId) {
    var repo = repositoryService.get(repoId);
    return new GetRepositoryRequest.Response(repositoryMapper.toDto(repo));
  }

  public static record ListBranchesRequest() {
    public record Response(List<BranchDto> branches) {}
  }

  @GET
  @Path("/{repoId}/branches")
  public ListBranchesRequest.Response branches(@PathParam("repoId") String repoId) {
    return new ListBranchesRequest.Response(repositoryService.listBranchesWithCleanup(repoId));
  }

  @GET
  @Path("/{repoId}/commits")
  public CommitLogDto commits(
      @PathParam("repoId") String repoId, @QueryParam("branch") @NotBlank String branch) {
    return commitService.listCommits(repoId, branch);
  }

  @GET
  @Path("/{repoId}/commits/{commitHash}/changes")
  public CommitChangesDto commitChanges(
      @PathParam("repoId") String repoId,
      @PathParam("commitHash") String commitHash,
      @QueryParam("parent") String parent) {
    return commitService.listChanges(repoId, commitHash, parent);
  }

  @GET
  @Path("/{repoId}/commits/{commitHash}/diff")
  public CommitFileDiffDto commitFileDiff(
      @PathParam("repoId") String repoId,
      @PathParam("commitHash") String commitHash,
      @QueryParam("parent") String parent,
      @QueryParam("path") @NotBlank String path) {
    return commitService.getFileDiff(repoId, commitHash, parent, path);
  }

  // SEAM (migration-plan.md §6, repository <-> workspace). Two routes stood here —
  // POST /repositories/{repoId}/branches/merge and .../branches/cleanup — and both were thin
  // forwards to WorkspaceService.mergeBranch / cleanupBranch, which is WS_REPO (qits-workspaces').
  // Branch integration is a workspace operation exposed on the repository path; it is cut rather
  // than ported, because a port whose whole body is "call the other context's service" is just a
  // dependency with extra steps.
  //
  // They are now owned: qits-workspaces serves both from its own BranchController, over the
  // WorkspaceService methods they always called — under its own segment, not this one.

  public static record DeleteBranchRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{repoId}/branches")
  public DeleteBranchRequest.Response deleteBranch(
      @PathParam("repoId") String repoId, @QueryParam("branch") @NotBlank String branch) {
    repositoryService.deleteBranch(repoId, branch);
    return new DeleteBranchRequest.Response(true);
  }

  public static record DeleteRepositoryRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{repoId}")
  public DeleteRepositoryRequest.Response delete(@PathParam("repoId") String repoId) {
    repositoryService.delete(repoId);
    return new DeleteRepositoryRequest.Response(true);
  }

  public static record PullRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/pull")
  public PullRepositoryRequest.Response pull(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginPullRepository(repoId);
    return new PullRepositoryRequest.Response(technicalProcessId);
  }

  public static record PushRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/push")
  public PushRepositoryRequest.Response push(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginPushRepository(repoId);
    return new PushRepositoryRequest.Response(technicalProcessId);
  }

  public static record SyncRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/sync")
  public SyncRepositoryRequest.Response sync(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginSyncRepository(repoId);
    return new SyncRepositoryRequest.Response(technicalProcessId);
  }

  @GET
  @Path("/{repoId}/sync-status")
  public SyncStatusDto syncStatus(@PathParam("repoId") String repoId) {
    return repositoryService.syncStatus(repoId);
  }

  public static record ActiveProcessRequest() {
    /**
     * The repository's currently-running technical process (pull/sync/push), or null when none is
     * live.
     */
    public record Response(String technicalProcessId) {}
  }

  /**
   * The id of the repository's live pull/sync process, if any — the reattach discovery endpoint for
   * the repository detail route (a reload / second tab reopens the streamed log from it). Returns
   * null when the repository exists but no process is live; an unknown/deleted repo is a 404 like
   * the sibling repository GETs, so a stale tab surfaces the gone repository rather than a silent
   * "none". Activeness changes ride the repository {@code PROCESS} SSE hint.
   */
  @GET
  @Path("/{repoId}/active-process")
  public ActiveProcessRequest.Response activeProcess(@PathParam("repoId") String repoId) {
    repositoryService.get(repoId); // 404 on an unknown/deleted repository
    return new ActiveProcessRequest.Response(
        technicalProcesses.isUnsatisfied()
            ? null
            : technicalProcesses.get().activeForRepository(repoId).orElse(null));
  }

  public static record SetMainBranchRequest(@NotBlank String branch) {
    public record Response(RepositoryDto repository) {}
  }

  @PUT
  @Path("/{repoId}/main-branch")
  public SetMainBranchRequest.Response setMainBranch(
      @PathParam("repoId") String repoId, @Valid SetMainBranchRequest request) {
    var repo = repositoryService.setMainBranch(repoId, request.branch());
    return new SetMainBranchRequest.Response(repositoryMapper.toDto(repo));
  }
}
