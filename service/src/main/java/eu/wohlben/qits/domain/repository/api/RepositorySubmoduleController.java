package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.dto.RepositorySubmoduleDto;
import eu.wohlben.qits.domain.repository.mapper.RepositorySubmoduleMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Lists a superproject's submodule edges — the sibling repositories qits imported for its {@code
 * .gitmodules} entries. Read-only: edges are created by importing a repository with submodules and
 * cascade-deleted with either endpoint, so there is no create/delete surface here.
 */
@Path("/repositories/{repositoryId}/submodules")
@Produces(MediaType.APPLICATION_JSON)
public class RepositorySubmoduleController {

  @Inject RepositoryService repositoryService;

  @Inject RepositorySubmoduleMapper repositorySubmoduleMapper;

  public static record ListRepositorySubmodulesRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(RepositorySubmoduleDto submodule) {}
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
    return new ListRepositorySubmodulesRequest.Response(entries);
  }
}
