package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.dto.RepositorySubmoduleDto;
import eu.wohlben.qits.domain.repository.mapper.RepositorySubmoduleMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A superproject's submodule surface: the imported edges (sibling repositories qits created for its
 * {@code .gitmodules} entries) plus the still-unimported ones — and the {@code import} action that
 * turns the latter into the former. Import is user-driven layer by layer: creating a repository
 * with the {@code importSubmodules} toggle imports its DIRECT submodules; going deeper is invoking
 * {@code import} on an imported child (each child's detail view offers it while anything is
 * available), as far down as the user cares to recurse. Edges cascade-delete with either
 * repository, so there is no delete surface here.
 */
@Path("/repositories/{repositoryId}/submodules")
@Produces(MediaType.APPLICATION_JSON)
public class RepositorySubmoduleController {

  @Inject RepositoryService repositoryService;

  @Inject RepositorySubmoduleMapper repositorySubmoduleMapper;

  public static record ListRepositorySubmodulesRequest() {
    public record Response(List<Entry> entries, List<Available> available) {
      public record Entry(RepositorySubmoduleDto submodule) {}

      /** A {@code .gitmodules} entry not yet imported; {@code url} comes back resolved. */
      public record Available(String name, String path, String url) {}
    }
  }

  @GET
  public ListRepositorySubmodulesRequest.Response list(
      @PathParam("repositoryId") String repositoryId) {
    var entries =
        repositoryService.listSubmodules(repositoryId).stream()
            .map(
                edge ->
                    new ListRepositorySubmodulesRequest.Response.Entry(
                        repositorySubmoduleMapper.toDto(edge)))
            .toList();
    var available =
        repositoryService.listUnimportedSubmodules(repositoryId).stream()
            .map(
                sub ->
                    new ListRepositorySubmodulesRequest.Response.Available(
                        sub.name(), sub.path(), sub.url()))
            .toList();
    return new ListRepositorySubmodulesRequest.Response(entries, available);
  }

  public static record ImportRepositorySubmodulesRequest() {
    public record Response(List<ListRepositorySubmodulesRequest.Response.Entry> entries) {}
  }

  /**
   * Imports the repository's still-unimported DIRECT submodules as sibling repositories (one level
   * — see the class doc) and returns the full edge list afterwards. Idempotent: children dedup by
   * resolved url within the project, edges by path.
   */
  @POST
  @Path("/import")
  public ImportRepositorySubmodulesRequest.Response importSubmodules(
      @PathParam("repositoryId") String repositoryId) {
    var entries =
        repositoryService.importDirectSubmodules(repositoryId).stream()
            .map(
                edge ->
                    new ListRepositorySubmodulesRequest.Response.Entry(
                        repositorySubmoduleMapper.toDto(edge)))
            .toList();
    return new ImportRepositorySubmodulesRequest.Response(entries);
  }
}
