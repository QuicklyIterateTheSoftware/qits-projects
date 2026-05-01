package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.dto.RepositoryDto;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.mapper.RepositoryMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

    @Inject
    RepositoryService repositoryService;

    @Inject
    RepositoryMapper repositoryMapper;

    public static record CloneRepositoryRequest(@NotBlank String url, RepositoryArchetype archetype) {
        public record Response(RepositoryDto repository) {}
    }

    @POST
    @Path("/{repoId}/clone")
    public CloneRepositoryRequest.Response clone(@PathParam("repoId") String repoId, @Valid CloneRepositoryRequest request) {
        var repo = repositoryService.cloneRepository(repoId, request.url(), request.archetype());
        return new CloneRepositoryRequest.Response(repositoryMapper.toDto(repo));
    }

    public static record PullRepositoryRequest() {
        public record Response(String output) {}
    }

    @POST
    @Path("/{repoId}/pull")
    public PullRepositoryRequest.Response pull(@PathParam("repoId") String repoId, @Valid PullRepositoryRequest request) {
        String output = repositoryService.pullRepository(repoId);
        return new PullRepositoryRequest.Response(output);
    }

    public static record PushRepositoryRequest() {
        public record Response(String output) {}
    }

    @POST
    @Path("/{repoId}/push")
    public PushRepositoryRequest.Response push(@PathParam("repoId") String repoId, @Valid PushRepositoryRequest request) {
        String output = repositoryService.pushRepository(repoId);
        return new PushRepositoryRequest.Response(output);
    }
}
