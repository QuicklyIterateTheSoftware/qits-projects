package eu.wohlben.qits.domain.project.api;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.project.dto.ProjectDto;
import eu.wohlben.qits.domain.project.mapper.ProjectMapper;
import eu.wohlben.qits.domain.repository.dto.RepositoryDto;
import eu.wohlben.qits.domain.repository.mapper.RepositoryMapper;
import jakarta.inject.Inject;
import eu.wohlben.qits.validation.NotBlankIfPresent;
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
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectController {

    @Inject
    ProjectService projectService;

    @Inject
    ProjectMapper projectMapper;

    @Inject
    RepositoryMapper repositoryMapper;

    // --- Project CRUD ---

    public static record CreateProjectRequest(
        @NotBlank String id,
        @NotBlank String name,
        String description
    ) {
        public record Response(ProjectDto project) {}
    }

    @POST
    public CreateProjectRequest.Response create(@Valid CreateProjectRequest request) {
        var project = projectService.create(request.id(), request.name(), request.description());
        return new CreateProjectRequest.Response(projectMapper.toDto(project));
    }

    public static record GetProjectRequest() {
        public record Response(ProjectDto project) {}
    }

    @GET
    @Path("/{id}")
    public GetProjectRequest.Response get(@PathParam("id") String id) {
        var project = projectService.get(id);
        return new GetProjectRequest.Response(projectMapper.toDto(project));
    }

    public static record ListProjectsRequest() {
        public record Response(List<Entry> entries) {
            public record Entry(ProjectDto project) {}
        }
    }

    @GET
    public ListProjectsRequest.Response list() {
        var projects = projectService.list();
        var entries = projects.stream()
            .map(p -> new ListProjectsRequest.Response.Entry(projectMapper.toDto(p)))
            .toList();
        return new ListProjectsRequest.Response(entries);
    }

    public static record UpdateProjectRequest(
        @NotBlankIfPresent String name,
        String description
    ) {
        public record Response(ProjectDto project) {}
    }

    @PUT
    @Path("/{id}")
    public UpdateProjectRequest.Response update(@PathParam("id") String id, @Valid UpdateProjectRequest request) {
        var project = projectService.update(id, request.name(), request.description());
        return new UpdateProjectRequest.Response(projectMapper.toDto(project));
    }

    public static record DeleteProjectRequest() {
        public record Response(boolean success) {}
    }

    @DELETE
    @Path("/{id}")
    public DeleteProjectRequest.Response delete(@PathParam("id") String id) {
        projectService.delete(id);
        return new DeleteProjectRequest.Response(true);
    }

    // --- Repository associations ---

    public static record ListProjectRepositoriesRequest() {
        public record Response(List<Entry> entries) {
            public record Entry(RepositoryDto repository) {}
        }
    }

    @GET
    @Path("/{projectId}/repositories")
    public ListProjectRepositoriesRequest.Response listRepositories(@PathParam("projectId") String projectId) {
        var repos = projectService.getRepositories(projectId);
        var entries = repos.stream()
            .map(r -> new ListProjectRepositoriesRequest.Response.Entry(repositoryMapper.toDto(r)))
            .toList();
        return new ListProjectRepositoriesRequest.Response(entries);
    }

    public static record CreateProjectRepositoryRequest(
        @NotBlank String id,
        @NotBlank String url,
        eu.wohlben.qits.domain.repository.entity.RepositoryArchetype archetype
    ) {
        public record Response(RepositoryDto repository, String projectId) {}
    }

    @POST
    @Path("/{projectId}/repositories")
    public CreateProjectRepositoryRequest.Response createRepository(
            @PathParam("projectId") String projectId,
            @Valid CreateProjectRepositoryRequest request) {
        var repo = projectService.createRepositoryUnderProject(projectId, request.id(), request.url(), request.archetype());
        return new CreateProjectRepositoryRequest.Response(repositoryMapper.toDto(repo), projectId);
    }

    public static record AssociateRepositoryRequest(
        @NotBlank String repositoryId
    ) {
        public record Response(RepositoryDto repository, String projectId) {}
    }

    @PUT
    @Path("/{projectId}/associate")
    public AssociateRepositoryRequest.Response associateRepository(
            @PathParam("projectId") String projectId,
            @Valid AssociateRepositoryRequest request) {
        var repo = projectService.associateRepository(projectId, request.repositoryId());
        return new AssociateRepositoryRequest.Response(repositoryMapper.toDto(repo), projectId);
    }

    public static record DisassociateRepositoryRequest() {
        public record Response(RepositoryDto repository) {}
    }

    @DELETE
    @Path("/{projectId}/associate/{repositoryId}")
    public DisassociateRepositoryRequest.Response disassociateRepository(
            @PathParam("projectId") String projectId,
            @PathParam("repositoryId") String repositoryId) {
        var repo = projectService.disassociateRepository(projectId, repositoryId);
        return new DisassociateRepositoryRequest.Response(repositoryMapper.toDto(repo));
    }
}
