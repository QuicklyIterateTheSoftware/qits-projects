package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.CommitService;
import eu.wohlben.qits.domain.repository.control.QitsConfigReconciler;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.dto.BranchDto;
import eu.wohlben.qits.domain.repository.dto.CommitChangesDto;
import eu.wohlben.qits.domain.repository.dto.CommitFileDiffDto;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.dto.RepositoryDto;
import eu.wohlben.qits.domain.repository.dto.SyncStatusDto;
import eu.wohlben.qits.domain.repository.mapper.RepositoryMapper;
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

  @Inject WorkspaceService workspaceService;

  @Inject QitsConfigReconciler qitsConfigReconciler;

  @Inject RepositoryMapper repositoryMapper;

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

  public static record MergeBranchRequest(@NotBlank String source, String target, String result) {
    /**
     * @param cleanedUp whether the integrated source workspace+branch was removed afterwards (it
     *     was fully merged with no dependents)
     */
    public record Response(
        String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}
  }

  @POST
  @Path("/{repoId}/branches/merge")
  public MergeBranchRequest.Response mergeBranch(
      @PathParam("repoId") String repoId, @Valid MergeBranchRequest request) {
    var result =
        workspaceService.mergeBranch(repoId, request.source(), request.target(), request.result());
    return new MergeBranchRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output(), result.cleanedUp());
  }

  public static record CleanupBranchRequest(@NotBlank String branch, String result) {
    public record Response(boolean success) {}
  }

  @POST
  @Path("/{repoId}/branches/cleanup")
  public CleanupBranchRequest.Response cleanupBranch(
      @PathParam("repoId") String repoId, @Valid CleanupBranchRequest request) {
    workspaceService.cleanupBranch(repoId, request.branch(), request.result());
    return new CleanupBranchRequest.Response(true);
  }

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
    public record Response(String output) {}
  }

  @POST
  @Path("/{repoId}/push")
  public PushRepositoryRequest.Response push(@PathParam("repoId") String repoId) {
    String output = repositoryService.pushRepository(repoId);
    return new PushRepositoryRequest.Response(output);
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

  public static record ReloadConfigRequest() {
    /** The repository after reconciliation — {@code configWarning} reports any problem, or null. */
    public record Response(RepositoryDto repository) {}
  }

  /** Manually re-reads and reconciles {@code .qits-config.yml} from the current main branch. */
  @POST
  @Path("/{repoId}/config/reload")
  public ReloadConfigRequest.Response reloadConfig(@PathParam("repoId") String repoId) {
    qitsConfigReconciler.reload(repoId);
    return new ReloadConfigRequest.Response(repositoryMapper.toDto(repositoryService.get(repoId)));
  }
}
