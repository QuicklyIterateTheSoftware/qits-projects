package eu.wohlben.qits.domain.project.api;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
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
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectController {

    @Inject
    ProjectService projectService;

    // --- Project CRUD ---

    public static record CreateProjectRequest(
        @NotBlank String id,
        @NotBlank String name,
        String description
    ) {
        public record Response(String id, String name, String description) {}
    }

    @POST
    public CreateProjectRequest.Response create(@Valid CreateProjectRequest request) {
        var project = projectService.create(request.id(), request.name(), request.description());
        return toResponse(project);
    }

    public static record GetProjectRequest() {
        public record Response(String id, String name, String description) {}
    }

    @GET
    @Path("/{id}")
    public GetProjectRequest.Response get(@PathParam("id") String id) {
        var project = projectService.get(id);
        return toGetResponse(project);
    }

    public static record ListProjectsRequest() {
        public record Response(List<Entry> entries) {
            public record Entry(String id, String name, String description) {}
        }
    }

    @GET
    public ListProjectsRequest.Response list() {
        var projects = projectService.list();
        var entries = projects.stream()
            .map(p -> new ListProjectsRequest.Response.Entry(p.id, p.name, p.description))
            .toList();
        return new ListProjectsRequest.Response(entries);
    }

    public static record UpdateProjectRequest(
        String name,
        String description
    ) {
        public record Response(String id, String name, String description) {}
    }

    @PUT
    @Path("/{id}")
    public UpdateProjectRequest.Response update(@PathParam("id") String id, @Valid UpdateProjectRequest request) {
        var project = projectService.update(id, request.name(), request.description());
        return toUpdateResponse(project);
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
            public record Entry(String id, String url, RepositoryArchetype archetype) {}
        }
    }

    @GET
    @Path("/{projectId}/repositories")
    public ListProjectRepositoriesRequest.Response listRepositories(@PathParam("projectId") String projectId) {
        var repos = projectService.getRepositories(projectId);
        var entries = repos.stream()
            .map(r -> new ListProjectRepositoriesRequest.Response.Entry(r.id, r.url, r.archetype))
            .toList();
        return new ListProjectRepositoriesRequest.Response(entries);
    }

    public static record CreateProjectRepositoryRequest(
        @NotBlank String id,
        @NotBlank String url,
        RepositoryArchetype archetype
    ) {
        public record Response(String id, String url, RepositoryArchetype archetype, String projectId) {}
    }

    @POST
    @Path("/{projectId}/repositories")
    public CreateProjectRepositoryRequest.Response createRepository(
            @PathParam("projectId") String projectId,
            @Valid CreateProjectRepositoryRequest request) {
        var repo = projectService.createRepositoryUnderProject(projectId, request.id(), request.url(), request.archetype());
        return new CreateProjectRepositoryRequest.Response(repo.id, repo.url, repo.archetype, projectId);
    }

    public static record AssociateRepositoryRequest(
        @NotBlank String repositoryId
    ) {
        public record Response(String id, String url, RepositoryArchetype archetype, String projectId) {}
    }

    @PUT
    @Path("/{projectId}/associate")
    public AssociateRepositoryRequest.Response associateRepository(
            @PathParam("projectId") String projectId,
            @Valid AssociateRepositoryRequest request) {
        var repo = projectService.associateRepository(projectId, request.repositoryId());
        return new AssociateRepositoryRequest.Response(repo.id, repo.url, repo.archetype, projectId);
    }

    public static record DisassociateRepositoryRequest() {
        public record Response(String id, String url, RepositoryArchetype archetype) {}
    }

    @DELETE
    @Path("/{projectId}/associate/{repositoryId}")
    public DisassociateRepositoryRequest.Response disassociateRepository(
            @PathParam("projectId") String projectId,
            @PathParam("repositoryId") String repositoryId) {
        var repo = projectService.disassociateRepository(projectId, repositoryId);
        return new DisassociateRepositoryRequest.Response(repo.id, repo.url, repo.archetype);
    }

    // --- Helpers ---

    private static CreateProjectRequest.Response toResponse(Project project) {
        return new CreateProjectRequest.Response(project.id, project.name, project.description);
    }

    private static GetProjectRequest.Response toGetResponse(Project project) {
        return new GetProjectRequest.Response(project.id, project.name, project.description);
    }

    private static UpdateProjectRequest.Response toUpdateResponse(Project project) {
        return new UpdateProjectRequest.Response(project.id, project.name, project.description);
    }
}
