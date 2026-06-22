package eu.wohlben.qits.domain.project.api;

import eu.wohlben.qits.domain.featureflow.control.FeatureFlowConfigurationService;
import eu.wohlben.qits.domain.featureflow.dto.FeatureFlowConfigurationDto;
import eu.wohlben.qits.domain.featureflow.mapper.FeatureFlowConfigurationMapper;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.project.dto.ProjectDto;
import eu.wohlben.qits.domain.project.mapper.ProjectMapper;
import eu.wohlben.qits.domain.repository.dto.RepositoryDto;
import eu.wohlben.qits.domain.repository.mapper.RepositoryMapper;
import eu.wohlben.qits.validation.NotBlankIfPresent;
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

  @Inject ProjectService projectService;

  @Inject ProjectMapper projectMapper;

  @Inject RepositoryMapper repositoryMapper;

  @Inject FeatureFlowConfigurationService featureFlowConfigurationService;

  @Inject FeatureFlowConfigurationMapper featureFlowConfigurationMapper;

  // --- Project CRUD ---

  public static record CreateProjectRequest(@NotBlank String name, String description) {
    public record Response(ProjectDto project) {}
  }

  @POST
  public CreateProjectRequest.Response create(@Valid CreateProjectRequest request) {
    var project = projectService.create(request.name(), request.description());
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
    var entries =
        projects.stream()
            .map(p -> new ListProjectsRequest.Response.Entry(projectMapper.toDto(p)))
            .toList();
    return new ListProjectsRequest.Response(entries);
  }

  public static record UpdateProjectRequest(@NotBlankIfPresent String name, String description) {
    public record Response(ProjectDto project) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateProjectRequest.Response update(
      @PathParam("id") String id, @Valid UpdateProjectRequest request) {
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
  public ListProjectRepositoriesRequest.Response listRepositories(
      @PathParam("projectId") String projectId) {
    var repos = projectService.getRepositories(projectId);
    var entries =
        repos.stream()
            .map(r -> new ListProjectRepositoriesRequest.Response.Entry(repositoryMapper.toDto(r)))
            .toList();
    return new ListProjectRepositoriesRequest.Response(entries);
  }

  public static record CreateProjectRepositoryRequest(
      @NotBlank String url,
      eu.wohlben.qits.domain.repository.entity.RepositoryArchetype archetype) {
    public record Response(RepositoryDto repository, String projectId) {}
  }

  @POST
  @Path("/{projectId}/repositories")
  public CreateProjectRepositoryRequest.Response createRepository(
      @PathParam("projectId") String projectId, @Valid CreateProjectRepositoryRequest request) {
    var repo =
        projectService.createRepositoryUnderProject(projectId, request.url(), request.archetype());
    return new CreateProjectRepositoryRequest.Response(repositoryMapper.toDto(repo), projectId);
  }

  // --- Feature Flow Configuration sub-resources ---

  public static record ListProjectFeatureFlowConfigurationsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(FeatureFlowConfigurationDto featureFlowConfiguration) {}
    }
  }

  @GET
  @Path("/{projectId}/feature-flow-configurations")
  public ListProjectFeatureFlowConfigurationsRequest.Response listFeatureFlowConfigurations(
      @PathParam("projectId") String projectId) {
    projectService.get(projectId); // verify project exists
    var configs = featureFlowConfigurationService.listByProject(projectId);
    var entries =
        configs.stream()
            .map(
                c ->
                    new ListProjectFeatureFlowConfigurationsRequest.Response.Entry(
                        featureFlowConfigurationMapper.toDto(c)))
            .toList();
    return new ListProjectFeatureFlowConfigurationsRequest.Response(entries);
  }

  public static record CreateProjectFeatureFlowConfigurationRequest(@NotBlank String name) {
    public record Response(FeatureFlowConfigurationDto featureFlowConfiguration) {}
  }

  @POST
  @Path("/{projectId}/feature-flow-configurations")
  public CreateProjectFeatureFlowConfigurationRequest.Response createFeatureFlowConfiguration(
      @PathParam("projectId") String projectId,
      @Valid CreateProjectFeatureFlowConfigurationRequest request) {
    var config = featureFlowConfigurationService.createUnderProject(projectId, request.name());
    return new CreateProjectFeatureFlowConfigurationRequest.Response(
        featureFlowConfigurationMapper.toDto(config));
  }
}
