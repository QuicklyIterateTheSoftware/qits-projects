package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.BackupPushService;
import eu.wohlben.qits.projects.control.BuildStatusLedger;
import eu.wohlben.qits.projects.control.CommitService;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.BranchDto;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.CommitChangesDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.CommitLogDto;
import eu.wohlben.qits.projects.dto.RepositoryCoordinatesDto;
import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.dto.SyncStatusDto;
import eu.wohlben.qits.projects.mapper.RepositoryMapper;
import jakarta.enterprise.inject.Instance;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class RepositoryController {

  @Inject RepositoryService repositoryService;

  /** The debounced backup runner both trigger routes hand off to. */
  @Inject BackupPushService backupPushService;

  @Inject CommitService commitService;

  /** The per-commit build-status ledger the bus keeps — see {@code bus/BuildStatusListener}. */
  @Inject BuildStatusLedger buildStatusLedger;

  @Inject RepositoryMapper repositoryMapper;

  /** SEAM: the technical-process framework is an optional port here (see the interface). */
  @Inject Instance<TechnicalProcessRegistry> technicalProcesses;

  public static record ListRepositoriesRequest() {
    /**
     * @param repositories every repository this service holds, sorted by project then name. A row
     *     with no registered name is present with a null {@code name}, and one whose name declares
     *     no role suffix with a null {@code archetype} — see {@link RepositoryCoordinatesDto}.
     */
    public record Response(List<RepositoryCoordinatesDto> repositories) {}
  }

  /**
   * The flat catalogue: every repository with the coordinates that address it publicly.
   *
   * <p><b>Two callers, two roles</b>, the same pair the by-id read below serves. qits-ci's trigger
   * engine reads it with its machine identity — it is the replacement for walking the git host's
   * internal {@code GET /git}, which answers opaque storage ids — and a browser session reads it
   * too. A method-level {@code @RolesAllowed} <b>replaces</b> the class-level {@code qits:admin}, so
   * naming only {@code qits:system} here would be a 403 for every browser.
   *
   * <p>This is a collection GET on the controller's own path; {@code /repositories/{repoId}} below
   * is a different template and neither shadows the other.
   *
   * <p>A read that fails is a 5xx and never an empty list: "no repositories" is an answer a caller
   * acts on. {@code RepositoryService#listCoordinates} holds it through a postgres cutover for the
   * same reason.
   *
   * <p><b>The answer carries each repository's archetype</b>, which is what makes one read enough
   * for a consumer keyed on the kind — qits-maintenance's release-train reader. It is an added
   * optional field and nothing had to change to keep reading this route: an existing client ignores
   * the key, and a null archetype is a row whose name declares no role suffix.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  @Operation(
      summary = "Every repository with its public coordinates",
      description =
          "The machine-readable catalogue: row id, project, addressable name, main branch and"
              + " archetype, sorted by project then name. A repository that owns no name is listed"
              + " with a null name; one whose name declares no role suffix carries a null"
              + " archetype.")
  public ListRepositoriesRequest.Response list() {
    return new ListRepositoriesRequest.Response(repositoryService.listCoordinates());
  }

  public static record GetRepositoryRequest() {
    public record Response(RepositoryDto repository) {}
  }

  /**
   * One repository by id — <b>two callers, two roles</b>, the same pair {@code
   * ProjectController#listRepositories} serves. qits-workspaces reads it with its machine identity
   * ({@code RepositoryLookup}, which is what tells a release which repository it is releasing), and
   * the workspaces detail screen reads it through a browser session for the repository's main
   * branch — the deep link carries no project id, so this by-id read is the only way in.
   *
   * <p>A method-level {@code @RolesAllowed} <b>replaces</b> the class-level {@code qits:admin}
   * rather than adding to it. {@code qits:system} alone was therefore a refusal of every browser
   * that reached this route.
   */
  @GET
  @Path("/{repoId}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  public GetRepositoryRequest.Response get(@PathParam("repoId") String repoId) {
    var repo = repositoryService.get(repoId);
    return new GetRepositoryRequest.Response(repositoryMapper.toDto(repo));
  }

  public static record ListBranchesRequest() {
    public record Response(List<BranchDto> branches) {}
  }

  @GET
  @Path("/{repoId}/branches")
  public ListBranchesRequest.Response branches(@PathParam("repoId") String repoId) {
    return new ListBranchesRequest.Response(repositoryService.listBranchesWithCleanup(repoId));
  }

  @GET
  @Path("/{repoId}/commits")
  public CommitLogDto commits(
      @PathParam("repoId") String repoId, @QueryParam("branch") @NotBlank String branch) {
    return commitService.listCommits(repoId, branch);
  }

  @GET
  @Path("/{repoId}/commits/{commitHash}/changes")
  public CommitChangesDto commitChanges(
      @PathParam("repoId") String repoId,
      @PathParam("commitHash") String commitHash,
      @QueryParam("parent") String parent) {
    return commitService.listChanges(repoId, commitHash, parent);
  }

  @GET
  @Path("/{repoId}/commits/{commitHash}/diff")
  public CommitFileDiffDto commitFileDiff(
      @PathParam("repoId") String repoId,
      @PathParam("commitHash") String commitHash,
      @QueryParam("parent") String parent,
      @QueryParam("path") @NotBlank String path) {
    return commitService.getFileDiff(repoId, commitHash, parent, path);
  }

  public static record ListCommitBuildsRequest() {
    /**
     * @param builds every CI run's terminal verdict about this commit, newest first, from the
     *     ledger the bus keeps. Empty means "no verdict recorded", which covers both a commit
     *     nothing built and a run still queued or running — only terminal runs announce.
     */
    public record Response(List<CommitBuildStatusDto> builds) {}
  }

  /**
   * The per-commit build-status ledger's read — <b>two callers, two roles</b>: a browser session
   * reading a commit's verdicts, and the machine peers the release-quality-gates work brings (the
   * release flow asking "is this sha green"). A method-level {@code @RolesAllowed} <b>replaces</b>
   * the class-level {@code qits:admin}, so both are spelled.
   */
  @GET
  @Path("/{repoId}/commits/{commitHash}/builds")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  @Operation(
      summary = "Every CI verdict recorded for one commit",
      description =
          "One entry per terminal CI run of this commit, newest first, fed from qits-ci's build"
              + " events. Queued and running builds do not appear; an empty list means no verdict"
              + " yet, never that the commit is fine.")
  public ListCommitBuildsRequest.Response commitBuilds(
      @PathParam("repoId") String repoId, @PathParam("commitHash") String commitHash) {
    return new ListCommitBuildsRequest.Response(buildStatusLedger.verdictsOf(repoId, commitHash));
  }

  // SEAM (migration-plan.md §6, repository <-> workspace). Two routes stood here —
  // POST /repositories/{repoId}/branches/merge and .../branches/cleanup — and both were thin
  // forwards to WorkspaceService.mergeBranch / cleanupBranch, which is WS_REPO (qits-workspaces').
  // Branch integration is a workspace operation exposed on the repository path; it is cut rather
  // than ported, because a port whose whole body is "call the other context's service" is just a
  // dependency with extra steps.
  //
  // They are now owned: qits-workspaces serves both from its own BranchController, over the
  // WorkspaceService methods they always called — under its own segment, not this one.

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
  @Operation(
      summary = "Delete a repository, on the git host as well",
      description =
          "Deletes the row, the repository's workspaces, its local mirror, its entry in the"
              + " project's wrapper if it is still there, and the repository itself on"
              + " qits-githost. The history is gone: there is no tombstone and no retention, and"
              + " a git host that fails the delete fails the whole request, leaving the row. The"
              + " project's wrapper repository is refused — delete the project instead.")
  public DeleteRepositoryRequest.Response delete(@PathParam("repoId") String repoId) {
    repositoryService.delete(repoId);
    return new DeleteRepositoryRequest.Response(true);
  }

  public static record PullRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/pull")
  public PullRepositoryRequest.Response pull(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginPullRepository(repoId);
    return new PullRepositoryRequest.Response(technicalProcessId);
  }

  public static record PushRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/push")
  public PushRepositoryRequest.Response push(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginPushRepository(repoId);
    return new PushRepositoryRequest.Response(technicalProcessId);
  }

  public static record SyncRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/sync")
  public SyncRepositoryRequest.Response sync(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginSyncRepository(repoId);
    return new SyncRepositoryRequest.Response(technicalProcessId);
  }

  public static record BackupSyncRequest() {
    /**
     * @param scheduled always true — the run is queued, not finished. What it came to lands on the
     *     repository's {@code lastBackup}, which is what a client polls or refetches.
     */
    public record Response(String repositoryId, boolean scheduled) {}
  }

  /**
   * Ask for this repository to be backed up to its forge twin now, rather than waiting for its next
   * push or the hourly sweep — the button beside a red backup status.
   *
   * <p>202 and not 200: the answer is "queued", and the run itself is debounced per repository, so
   * an impatient second click folds into the first rather than starting a second push against the
   * same mirror. A repository with no twin is accepted and does nothing, for the same reason the
   * git host's intake accepts one: the caller cannot act on that distinction.
   */
  @POST
  @Path("/{repoId}/backup-sync")
  @ResponseStatus(202)
  @Operation(
      summary = "Back this repository up to its forge twin now",
      description =
          "Schedules the same backup the git host's post-receive triggers, debounced per repository."
              + " The outcome lands on the repository's lastBackup rather than in this response.")
  @APIResponse(
      responseCode = "202",
      description = "Queued",
      content =
          @Content(schema = @Schema(implementation = BackupSyncRequest.Response.class)))
  @APIResponse(responseCode = "404", description = "No such repository")
  public BackupSyncRequest.Response backupSync(@PathParam("repoId") String repoId) {
    repositoryService.get(repoId); // 404 for an unknown id, like every other repository route
    backupPushService.onPush(repoId);
    return new BackupSyncRequest.Response(repoId, true);
  }

  @GET
  @Path("/{repoId}/sync-status")
  public SyncStatusDto syncStatus(@PathParam("repoId") String repoId) {
    return repositoryService.syncStatus(repoId);
  }

  public static record ActiveProcessRequest() {
    /**
     * The repository's currently-running technical process (pull/sync/push), or null when none is
     * live.
     */
    public record Response(String technicalProcessId) {}
  }

  /**
   * The id of the repository's live pull/sync process, if any — the reattach discovery endpoint for
   * the repository detail route (a reload / second tab reopens the streamed log from it). Returns
   * null when the repository exists but no process is live; an unknown/deleted repo is a 404 like
   * the sibling repository GETs, so a stale tab surfaces the gone repository rather than a silent
   * "none". Activeness changes ride the repository {@code PROCESS} SSE hint.
   */
  @GET
  @Path("/{repoId}/active-process")
  public ActiveProcessRequest.Response activeProcess(@PathParam("repoId") String repoId) {
    repositoryService.get(repoId); // 404 on an unknown/deleted repository
    return new ActiveProcessRequest.Response(
        technicalProcesses.isUnsatisfied()
            ? null
            : technicalProcesses.get().activeForRepository(repoId).orElse(null));
  }

  /**
   * @param name the new project-scoped addressable name — what {@code /git/<projectId>/<name>} will
   *     serve and what a committed {@code ../<name>.git} will resolve to. Same shape rule as
   *     creation: 1-64 characters of letters, digits and inner dashes.
   */
  public static record RenameRepositoryRequest(@NotBlank String name) {
    /**
     * @param previousName what it answered to before — null for a row that answered to nothing, and
     *     equal to the repository's current name when the rename was a no-op
     * @param changed false when the repository already answered to exactly this name and nothing
     *     was written or announced
     */
    public record Response(RepositoryDto repository, String previousName, boolean changed) {}
  }

  /**
   * Renames a repository — the only operation on this platform that changes a repository's public
   * identity, and the phase-2 prerequisite the rename campaign is ordered after.
   *
   * <p><b>PATCH and not PUT</b>, because the body is one field of the repository rather than a
   * replacement for it: {@code PUT /{repoId}/main-branch} is a sub-resource whose whole state is the
   * branch, and there is no such sub-resource for a name — the name is the row's own coordinate, so
   * the verb belongs on the row.
   *
   * <p><b>Nothing is asked of the git host.</b> A bare is keyed by the row's opaque id, so the same
   * bare answers under the new name the moment this returns; see {@code RepositoryService#rename}
   * for what that costs and what it does not.
   */
  @jakarta.ws.rs.PATCH
  @Path("/{repoId}")
  @Operation(
      summary = "Rename a repository",
      description =
          "Gives the repository a new project-scoped addressable name. Nothing moves on the git"
              + " host — a bare is keyed by the repository's id — so /git/<project>/<newName>"
              + " serves it immediately and the old name stops resolving. The archetype is"
              + " re-derived from the new name's role suffix (-service, -daemon, -frontend, -cli,"
              + " -oci, -javalib, -jslib) and left as it was when the new name declares none. The"
              + " project's wrapper is NOT rewritten: update the .gitmodules entry to the new name"
              + " and push, or the repository reads as UNDECLARED until you do. The backup twin"
              + " self-heals from the wrapper on the next reconcile.")
  @APIResponse(responseCode = "200", description = "The repository answers to the new name")
  @APIResponse(
      responseCode = "400",
      description =
          "An invalid name, a name another repository in this project already answers to, or the"
              + " project's wrapper repository (whose name is derived from the immutable slug)")
  @APIResponse(responseCode = "404", description = "No such repository")
  public RenameRepositoryRequest.Response rename(
      @PathParam("repoId") String repoId, @Valid RenameRepositoryRequest request) {
    var renamed = repositoryService.rename(repoId, request.name());
    // Re-read rather than mapping what the rename handed back: that write owns its own transaction,
    // so the row it saw is detached by now and the mapper follows a relation off it.
    return new RenameRepositoryRequest.Response(
        repositoryMapper.toDto(repositoryService.get(repoId)),
        renamed.previousName(),
        renamed.changed());
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
