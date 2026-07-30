package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.dto.ProjectDto;
import eu.wohlben.qits.projects.mapper.ProjectMapper;
import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.mapper.RepositoryMapper;
import eu.wohlben.qits.projects.validation.DnsFqdn;
import eu.wohlben.qits.projects.validation.DnsRecordValue;
import eu.wohlben.qits.projects.validation.NotBlankIfPresent;
import eu.wohlben.qits.projects.validation.ProjectSlug;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

  // --- Project CRUD ---

  /**
   * @param slug the git-safe, immutable project identity the wrapper repository is named after
   *     ({@code <slug>-<slug>}). Optional — derived from {@code name} when omitted. Unlike {@code
   *     name} it can never be changed afterwards, so a wrapper's alias cannot go stale.
   * @param url an existing upstream to adopt as the wrapper repository. Optional — omitted, the
   *     wrapper is initialized locally with no backup remote. An adopted upstream may be completely
   *     empty (it is seeded with the project template skeleton) but its basename must be exactly
   *     {@code <slug>-<slug>}.
   * @param dns the domain the project resolves through — <b>required</b>. A project is a deployable
   *     application and a deployable application has an address; asking for it here is what stops
   *     "which hostname is this?" from being a question answered later, by hand, per project. The
   *     columns behind it are nullable and {@code ProjectDto} carries null back out, because rows
   *     created before this field existed and self-seeded projects without a configured domain both
   *     exist — but there is no way to create a new one that way. See {@code ProjectDnsRecord} for
   *     why the whole field is a declared placeholder.
   */
  public static record CreateProjectRequest(
      @NotBlank String name,
      @ProjectSlug String slug,
      String description,
      String url,
      @NotNull @Valid DnsSpec dns) {
    /**
     * The record handed to qits-dns verbatim.
     *
     * <p>Its own type rather than {@code ProjectDnsRecordDto}: the response DTO is nullable in all
     * three fields (a legacy row has none) while every field here is required, and one shared type
     * would have to describe the looser of the two — which would put the requiredness of a
     * <em>request</em> field out of the document a client is generated from.
     *
     * @param domain the whole fully-qualified name, lowercase, never zone-relative
     * @param value the address or CNAME target — required for every type, a CNAME included
     */
    public record DnsSpec(
        @NotNull @DnsFqdn String domain,
        @NotNull ProjectDnsRecordType type,
        @NotNull @DnsRecordValue String value) {}

    /**
     * @param wrapper the wrapper repository project creation always ends with.
     */
    public record Response(ProjectDto project, RepositoryDto wrapper) {}
  }

  @POST
  public CreateProjectRequest.Response create(@Valid CreateProjectRequest request) {
    var project =
        projectService.create(
            request.name(),
            request.slug(),
            request.description(),
            request.url(),
            toEntity(request.dns()));
    var wrapper = projectService.findWrapper(project.id).orElseThrow();
    return new CreateProjectRequest.Response(
        projectMapper.toDto(project), repositoryMapper.toDto(wrapper));
  }

  /**
   * The request's dns object as the embeddable the service stores. Null-tolerant even though the
   * field is {@code @NotNull}: Bean Validation is the guard, not this method, and a null here would
   * be a validation hole rather than something to throw a second, worse error about.
   */
  private static ProjectDnsRecord toEntity(CreateProjectRequest.DnsSpec dns) {
    return dns == null ? null : new ProjectDnsRecord(dns.domain(), dns.type(), dns.value());
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

  /**
   * {@code importSubmodules} (default true) imports the repository's DIRECT {@code .gitmodules}
   * submodules as sibling repositories — one level only; deeper levels are imported layer by layer
   * via the repository's own {@code POST /submodules/import} action.
   */
  public static record CreateProjectRepositoryRequest(
      @NotBlank String url,
      eu.wohlben.qits.projects.entity.RepositoryArchetype archetype,
      Boolean importSubmodules) {
    public record Response(RepositoryDto repository, String projectId) {}
  }

  @POST
  @Path("/{projectId}/repositories")
  public CreateProjectRepositoryRequest.Response createRepository(
      @PathParam("projectId") String projectId, @Valid CreateProjectRepositoryRequest request) {
    var repo =
        projectService.createRepositoryUnderProject(
            projectId,
            request.url(),
            request.archetype(),
            request.importSubmodules() == null || request.importSubmodules());
    return new CreateProjectRepositoryRequest.Response(repositoryMapper.toDto(repo), projectId);
  }

  // SEAM (migration-plan.md §6, project <-> featureflow). The two feature-flow sub-resources
  // (GET/POST /projects/{projectId}/feature-flow-configurations) are cut, NOT ported. Unlike the
  // workspaces edges there is no other side to declare a port against: domain.featureflow is
  // monolith-only and deferred (§9 item 6), coupled to project in both directions, with no target
  // repository at all. An application that wants those two routes back must reinstate them
  // alongside whatever eventually owns featureflow.
}
