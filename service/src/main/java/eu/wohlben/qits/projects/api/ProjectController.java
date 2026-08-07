package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.BackupPushService;
import eu.wohlben.qits.projects.control.ProjectReconcileService;
import eu.wohlben.qits.projects.control.ProjectReconciliation;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.WrapperReconcileService;
import eu.wohlben.qits.projects.dto.ProjectDto;
import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.entity.ProjectDnsRecord;
import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;
import eu.wohlben.qits.projects.mapper.ProjectMapper;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectController {

  @Inject ProjectService projectService;

  /** The manual drift remedy's orchestration — see {@link #reconcile}. */
  @Inject ProjectReconcileService projectReconcileService;

  /** The wrapper-driven repository reconcile — see {@link #reconcileRepositories}. */
  @Inject WrapperReconcileService wrapperReconcileService;

  /** The debounced backup runner the project-wide trigger hands off to. */
  @Inject BackupPushService backupPushService;

  @Inject RepositoryService repositoryService;

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

  /**
   * The manual drift remedy (main-environment-plan.md §5): re-assert this project's stored dns
   * record against qits-dns, synchronously, and answer with what it came to.
   *
   * <p><b>A failure is still a 200.</b> The outcome <em>is</em> the result, so the only error is the
   * one thing that makes the request itself wrong: an unknown project, a 404 like every other
   * project route.
   *
   * <p>It used to re-assert a deployment environment against qits-cd too. qits-cd owns environments
   * now — deliberate tiers created over its REST surface, not one per project.
   */
  public static record ReconcileProjectRequest() {
    /**
     * @param domain what re-asserting the dns record came to — {@code NOT_CONFIGURED} when the
     *     project stores none, which is not a failure
     * @param domainDetail why, when the outcome does not say it: the name no zone contained, the
     *     receiver's refusal, or that no registrar is wired at all. Null when there is nothing to
     *     add.
     */
    public record Response(ProjectReconciliation.DomainOutcome domain, String domainDetail) {}
  }

  /**
   * Deliberately <b>not</b> {@code @Operation(hidden = true)}: this is the one operation in this
   * controller a person invokes on purpose — the qits-ci {@code cancelRun} precedent — so it
   * belongs in the document a client is generated from.
   */
  @POST
  @Path("/{projectId}/reconcile")
  @Operation(
      summary = "Re-assert a project's domain against qits-dns",
      description =
          "Project creation registers the dns record fire-and-forget, so a receiver that was down"
              + " when a project was created leaves the record missing with nothing to carry it"
              + " forward. This re-asserts it synchronously and reports the outcome; the receiver is"
              + " idempotent, so it is safe to repeat, and it is also how a project created before"
              + " the hook existed gets its record.")
  @APIResponse(
      responseCode = "200",
      description =
          "The reconcile ran. A failed re-assertion is still a 200 — the outcome is the result.")
  @APIResponse(responseCode = "404", description = "No such project")
  public ReconcileProjectRequest.Response reconcile(@PathParam("projectId") String projectId) {
    var reconciliation = projectReconcileService.reconcile(projectId);
    return new ReconcileProjectRequest.Response(
        reconciliation.domain().outcome(), reconciliation.domain().detail());
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
    /**
     * @param wrapper the project's manifest as the wrapper's {@code .gitmodules} declares it, or
     *     null for a project with no wrapper repository (only projects predating wrappers). An
     *     entry with no {@code repositoryId}, or a repository named by no entry, is exactly the
     *     drift the reconcile action resolves.
     */
    public record Response(List<Entry> entries, WrapperReconcileService.WrapperView wrapper) {
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
    return new ListProjectRepositoriesRequest.Response(
        entries, wrapperReconcileService.view(projectId));
  }

  /**
   * Adds a component to the project — and to its wrapper repository, which is the same statement
   * made twice.
   *
   * @param url an existing repository to attach, mirrored in and published to the platform's git
   *     host. Exactly one of this and {@code name}.
   * @param name a blank repository to create on the platform's git host, seeded with the repository
   *     template. It becomes the component's addressable name, so it is what {@code ../<name>.git}
   *     resolves to.
   * @param archetype which kind of component this is — it decides the wrapper directory the
   *     submodule is mounted under, so it must be a placeable one.
   */
  public static record CreateProjectRepositoryRequest(
      String url,
      String name,
      @NotNull eu.wohlben.qits.projects.entity.RepositoryArchetype archetype) {
    /**
     * @param wrapperPath where the wrapper now mounts it, e.g. {@code services/checkout}
     */
    public record Response(RepositoryDto repository, String projectId, String wrapperPath) {}
  }

  @POST
  @Path("/{projectId}/repositories")
  @APIResponse(responseCode = "200", description = "The repository exists and the wrapper names it")
  @APIResponse(
      responseCode = "400",
      description =
          "Neither or both of url/name, an unplaceable archetype, a name already taken in the"
              + " project, or a wrapper commit the git host refused")
  public CreateProjectRepositoryRequest.Response createRepository(
      @PathParam("projectId") String projectId, @Valid CreateProjectRepositoryRequest request) {
    var created =
        projectService.createRepository(
            projectId, request.url(), request.name(), request.archetype());
    return new CreateProjectRepositoryRequest.Response(
        repositoryMapper.toDto(created.repository()), projectId, created.wrapperPath());
  }

  public static record BackupSyncProjectRequest() {
    /**
     * @param scheduled how many repositories were queued — every row of the project that has a forge
     *     twin. A row without one is not counted, because nothing was scheduled for it.
     */
    public record Response(String projectId, int scheduled) {}
  }

  /**
   * Back every repository of this project up to its forge twin now — the project-wide form of the
   * per-repository button, for the case a sign-in has just fixed the credentials all of them were
   * failing on.
   *
   * <p>202: the runs are queued and debounced per repository, so pressing it twice schedules the
   * same work once. Each outcome lands on its own repository's {@code lastBackup}; there is no
   * aggregate verdict to wait for, and inventing one would mean holding the request open across
   * every forge this project pushes to.
   */
  @POST
  @Path("/{projectId}/repositories/backup-sync")
  @ResponseStatus(202)
  @Operation(
      summary = "Back up every repository of this project to its forge twin",
      description =
          "Schedules a backup per repository that has a twin, debounced per repository. Outcomes"
              + " land on each repository's lastBackup rather than in this response.")
  @APIResponse(
      responseCode = "202",
      description = "Queued; the body says how many",
      content =
          @Content(schema = @Schema(implementation = BackupSyncProjectRequest.Response.class)))
  @APIResponse(responseCode = "404", description = "No such project")
  public BackupSyncProjectRequest.Response backupSyncProject(
      @PathParam("projectId") String projectId) {
    projectService.get(projectId); // 404 for an unknown project, like every other project route
    List<String> repoIds = repositoryService.repositoryIdsWithBackupTwin(projectId);
    repoIds.forEach(backupPushService::onPush);
    return new BackupSyncProjectRequest.Response(projectId, repoIds.size());
  }

  public static record ReconcileProjectRepositoriesRequest() {
    /**
     * @param entries one line per wrapper entry, plus one per row the wrapper no longer names
     */
    public record Response(
        String projectId,
        String wrapperRepositoryId,
        String branch,
        List<WrapperReconcileService.EntryOutcome> entries) {}
  }

  /**
   * Distinct from {@code POST /{projectId}/reconcile}, which re-asserts the project's dns record.
   * This one reconciles the project's <em>repositories</em> against its wrapper.
   */
  @POST
  @Path("/{projectId}/repositories/reconcile")
  @Operation(
      summary = "Bring a project's repositories in line with its wrapper's .gitmodules",
      description =
          "The wrapper repository is the project's configuration: every submodule entry gets a"
              + " repository (adopted when the git host already serves it, cloned from the entry's"
              + " backend otherwise), the directory an entry sits under decides its archetype, and"
              + " a repository no entry names is deregistered — its row goes, its history on the"
              + " git host stays. Idempotent, and the way a project imported from a wrapper url is"
              + " materialized.")
  @APIResponse(
      responseCode = "200",
      description =
          "The reconcile ran. A per-entry failure is still a 200 — the outcomes are the result.")
  @APIResponse(responseCode = "400", description = "The project has no wrapper repository")
  @APIResponse(responseCode = "404", description = "No such project")
  public ReconcileProjectRepositoriesRequest.Response reconcileRepositories(
      @PathParam("projectId") String projectId) {
    var reconciliation = wrapperReconcileService.reconcile(projectId);
    return new ReconcileProjectRepositoriesRequest.Response(
        reconciliation.projectId(),
        reconciliation.wrapperRepositoryId(),
        reconciliation.branch(),
        reconciliation.entries());
  }

  // SEAM (migration-plan.md §6, project <-> featureflow). The two feature-flow sub-resources
  // (GET/POST /projects/{projectId}/feature-flow-configurations) are cut, NOT ported. Unlike the
  // workspaces edges there is no other side to declare a port against: domain.featureflow is
  // monolith-only and deferred (§9 item 6), coupled to project in both directions, with no target
  // repository at all. An application that wants those two routes back must reinstate them
  // alongside whatever eventually owns featureflow.
}
