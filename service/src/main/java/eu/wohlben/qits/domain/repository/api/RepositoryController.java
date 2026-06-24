package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.RepositoryService;
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
    public record Response(List<String> branches) {}
  }

  @GET
  @Path("/{repoId}/branches")
  public ListBranchesRequest.Response branches(@PathParam("repoId") String repoId) {
    return new ListBranchesRequest.Response(repositoryService.listBranches(repoId));
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
    public record Response(String output) {}
  }

  @POST
  @Path("/{repoId}/pull")
  public PullRepositoryRequest.Response pull(@PathParam("repoId") String repoId) {
    String output = repositoryService.pullRepository(repoId);
    return new PullRepositoryRequest.Response(output);
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
    public record Response(String output) {}
  }

  @POST
  @Path("/{repoId}/sync")
  public SyncRepositoryRequest.Response sync(@PathParam("repoId") String repoId) {
    String output = repositoryService.syncRepository(repoId);
    return new SyncRepositoryRequest.Response(output);
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
}
