package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.WorktreeHistoryService;
import eu.wohlben.qits.domain.repository.dto.WorktreeHistoryDetailDto;
import eu.wohlben.qits.domain.repository.dto.WorktreeHistoryDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The worktree history for a repository: every worktree (active + resolved) as a browsable record
 * of the work that flowed through the repo. Keyed by the surrogate id, since worktree ids are
 * reusable once resolved.
 */
@Path("/repositories/{repoId}/history")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorktreeHistoryController {

  @Inject WorktreeHistoryService worktreeHistoryService;

  public static record ListHistoryRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(WorktreeHistoryDto worktree) {}
    }
  }

  @GET
  public ListHistoryRequest.Response list(@PathParam("repoId") String repoId) {
    var entries =
        worktreeHistoryService.list(repoId).stream()
            .map(ListHistoryRequest.Response.Entry::new)
            .toList();
    return new ListHistoryRequest.Response(entries);
  }

  public static record GetHistoryRequest() {
    public record Response(WorktreeHistoryDetailDto worktree) {}
  }

  @GET
  @Path("/{id}")
  public GetHistoryRequest.Response get(
      @PathParam("repoId") String repoId, @PathParam("id") Long id) {
    return new GetHistoryRequest.Response(worktreeHistoryService.get(repoId, id));
  }

  public static record UpdateHistoryRequest(String preamble, String result) {
    public record Response(WorktreeHistoryDetailDto worktree) {}
  }

  @PATCH
  @Path("/{id}")
  public UpdateHistoryRequest.Response update(
      @PathParam("repoId") String repoId, @PathParam("id") Long id, UpdateHistoryRequest request) {
    return new UpdateHistoryRequest.Response(
        worktreeHistoryService.updateNarrative(repoId, id, request.preamble(), request.result()));
  }
}
