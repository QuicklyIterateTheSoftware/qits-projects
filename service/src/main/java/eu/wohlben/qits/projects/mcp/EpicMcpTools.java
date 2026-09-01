package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.control.FeatureService;
import eu.wohlben.qits.epics.control.TaskService;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * The epic-refinement half of the "repository" MCP server — the surface a per-project refinement
 * agent drafts epics through, mounted on the same declared server as {@link RepositoryMcpTools}
 * ({@code /projects/mcp}) rather than on a second one: a new server name would need its own
 * declaration and its own daemon-side contract, and the agent wants both surfaces in one session
 * anyway (it reads the repositories it is planning work in).
 *
 * <p><strong>Use case: drafting and refining a plan.</strong> The agent lists the project's epics,
 * proposes a new one or extends a draft, and fills in its feature/task tree. Freezing a draft is
 * deliberately <em>not</em> here — moving an epic to IMPLEMENTATION is a human act in the UI, so
 * there is no transition tool for the model to reach for.
 *
 * <p>Scope comes from {@link ProjectScope} (the {@code X-QITS-Project} header), never from a tool
 * argument, and every id a tool is handed is checked back to that project — an epic, feature or
 * task in another project reads as not found, so the model cannot draft across project boundaries.
 *
 * <p>{@link WrapBusinessError} turns anything a tool throws — the scoping checks here and the
 * epics services' {@code NotFoundException}/{@code BadRequestException}/{@code ConflictException} —
 * into a tool result with {@code isError=true} carrying the message. That matters most for the
 * freeze: writing to a frozen epic comes back as a readable refusal the model can act on, not as a
 * JSON-RPC protocol error that kills the turn.
 *
 * <p>Every mutating tool fires a {@link ProjectChangeHint} on the project's SSE channel, so a
 * browser watching the epics overview redraws as the agent works.
 *
 * <p><strong>No {@code @Transactional} here</strong>, unlike {@link RepositoryMcpTools} — and that
 * is not an oversight. These tools straddle two persistence units: the scope checks read {@code
 * epics} and the repository cross-check reads {@code projects}. Both datasources are local (non-XA)
 * resources, and Narayana can enlist only one of those per transaction, so wrapping a tool in one
 * aborts the second enlistment with "Enlisted connection used without active transaction" — and the
 * wedged pooled connection then fails unrelated writes elsewhere in the process. The epics services
 * each open their own transaction, exactly as they do for the REST controllers, which are
 * transaction-free for the same reason.
 */
@ApplicationScoped
@WrapBusinessError
public class EpicMcpTools {

  /**
   * What the audit log records for a write with no forwarded identity. An MCP session is a machine
   * caller; naming it beats a null {@code changed_by} that reads as "unknown human".
   */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject ProjectScopeGuard scopeGuard;

  @Inject EpicService epicService;

  @Inject FeatureService featureService;

  @Inject TaskService taskService;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  // --- Result shapes --------------------------------------------------------

  /** An epic as it appears in a list: no tree, just enough to choose one and read its phase. */
  public record EpicSummary(
      String id, String slug, String title, String status, String description) {}

  /** A task inside {@link EpicDetail}. */
  public record TaskDetail(
      String id,
      String slug,
      String title,
      String description,
      String repositoryId,
      String dependsOnTaskId,
      Instant implementedAt) {}

  /** A feature inside {@link EpicDetail}, with its tasks. */
  public record FeatureDetail(
      String id,
      String slug,
      String title,
      String description,
      String dependsOnFeatureId,
      Instant implementedOn,
      List<TaskDetail> tasks) {}

  /** One epic with its whole feature/task tree. */
  public record EpicDetail(
      String id,
      String slug,
      String title,
      String status,
      String description,
      String supersededByEpicId,
      List<FeatureDetail> features) {}

  /** A feature on its own, as returned by the feature write tools. */
  public record FeatureSummary(
      String id, String slug, String title, String description, String dependsOnFeatureId) {}

  /** A task on its own, as returned by the task write tools. */
  public record TaskSummary(
      String id,
      String slug,
      String title,
      String description,
      String repositoryId,
      String dependsOnTaskId) {}

  // --- Epics ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_epics",
      description =
          "List the epics of the project this session is scoped to, oldest first, without their"
              + " feature/task tree. Start here: call it with status=\"REFINING\" to find the"
              + " drafts that are open for editing, and decide between extending one of them and"
              + " proposing a new epic. Only REFINING epics can be changed at all; the other"
              + " statuses (IMPLEMENTATION, IMPLEMENTED, SUPERSEDED, ABANDONED) are read-only"
              + " records.")
  public List<EpicSummary> listEpics(
      @ToolArg(
              required = false,
              description =
                  "exact status to filter by: REFINING, IMPLEMENTATION, IMPLEMENTED, SUPERSEDED or"
                      + " ABANDONED. Omit for every epic of the project.")
          String status) {
    return epicService.listByProject(scope.requireProjectId(), status).stream()
        .map(EpicMcpTools::summarize)
        .toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_epic",
      description =
          "Read one epic of this project in full: its description plus every feature and, under"
              + " each, every task. Use it before editing a draft, so the tree you extend is the"
              + " one that exists. The implemented markers it reports are set by people as work"
              + " ships — they are not yours to write.")
  public EpicDetail getEpic(
      @ToolArg(description = "id of an epic in this project") String id) {
    Epic epic = requireEpicInProject(id);
    List<FeatureDetail> features =
        featureService.listByEpic(epic.id).stream()
            .map(
                feature ->
                    new FeatureDetail(
                        feature.id,
                        feature.slug,
                        feature.title,
                        feature.description,
                        feature.dependsOnFeatureId,
                        feature.implementedOn,
                        taskService.listByFeature(feature.id).stream()
                            .map(
                                task ->
                                    new TaskDetail(
                                        task.id,
                                        task.slug,
                                        task.title,
                                        task.description,
                                        task.repositoryId,
                                        task.dependsOnTaskId,
                                        task.implementedAt))
                            .toList()))
            .toList();
    return new EpicDetail(
        epic.id,
        epic.slug,
        epic.title,
        epic.status.name(),
        epic.description,
        epic.supersededByEpicId,
        features);
  }

  @McpServer("repository")
  @Tool(
      name = "propose_epic",
      description =
          "Propose a new epic for this project. It is created as a REFINING draft — nothing is"
              + " committed to and no branches are cut — so this is the right move whenever the"
              + " work does not belong under an existing draft. Freezing the draft into"
              + " implementation is a human decision made in the UI; you cannot do it.")
  public EpicSummary proposeEpic(
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown spine") String description) {
    Epic epic = epicService.create(scope.requireProjectId(), title, description, changedBy());
    announce();
    return summarize(epic);
  }

  @McpServer("repository")
  @Tool(
      name = "update_epic",
      description =
          "Change a REFINING epic's title or description. Omitted fields keep their current value."
              + " Refused with a message once the epic leaves REFINING: its scope is frozen from"
              + " then on, and the fix is to propose a new epic rather than to edit this one.")
  public EpicSummary updateEpic(
      @ToolArg(description = "id of an epic in this project") String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description) {
    Epic current = requireEpicInProject(id);
    Epic epic =
        epicService.update(
            id,
            (title == null || title.isBlank()) ? current.title : title,
            description == null ? current.description : description,
            changedBy());
    announce();
    return summarize(epic);
  }

  // --- Features -------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "add_feature",
      description =
          "Add a feature to a REFINING epic of this project. A feature is one shippable slice of"
              + " the epic; give it a body that says what it is, not how far along it is. Refused"
              + " once the epic leaves REFINING.")
  public FeatureSummary addFeature(
      @ToolArg(description = "id of a REFINING epic in this project") String epicId,
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown body") String description,
      @ToolArg(
              required = false,
              description =
                  "id of another feature IN THE SAME EPIC that this one depends on; omit for none")
          String dependsOnFeatureId) {
    requireEpicInProject(epicId);
    Feature feature =
        featureService.create(epicId, title, description, dependsOnFeatureId, changedBy());
    announce();
    return summarize(feature);
  }

  @McpServer("repository")
  @Tool(
      name = "update_feature",
      description =
          "Change a feature of a REFINING epic. Omitted fields keep their current value. Refused"
              + " once the owning epic leaves REFINING. The implemented marker is not editable"
              + " here — that is recorded by people as work ships.")
  public FeatureSummary updateFeature(
      @ToolArg(description = "id of a feature in this project") String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(
              required = false,
              description =
                  "id of another feature in the same epic to depend on; omit to keep the current"
                      + " one")
          String dependsOnFeatureId) {
    requireFeatureInProject(id);
    Feature feature =
        featureService.update(
            id, title, description, dependsOnFeatureId, false, null, false, changedBy());
    announce();
    return summarize(feature);
  }

  @McpServer("repository")
  @Tool(
      name = "remove_feature",
      description =
          "Remove a feature of a REFINING epic, along with its tasks. Refused once the owning epic"
              + " leaves REFINING.")
  public String removeFeature(
      @ToolArg(description = "id of a feature in this project") String id) {
    requireFeatureInProject(id);
    featureService.delete(id, changedBy());
    announce();
    return "Removed feature " + id;
  }

  // --- Tasks ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "add_task",
      description =
          "Add a task to a feature of a REFINING epic. A task is the work in ONE repository, so"
              + " split a feature that spans several. Use list_repositories to pick a repositoryId;"
              + " it has to belong to this project. Refused once the owning epic leaves REFINING.")
  public TaskSummary addTask(
      @ToolArg(description = "id of a feature in this project") String featureId,
      @ToolArg(description = "id of a repository in this project — see list_repositories")
          String repositoryId,
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown body") String description,
      @ToolArg(
              required = false,
              description =
                  "id of another task IN THE SAME FEATURE that this one depends on; omit for none")
          String dependsOnTaskId) {
    requireFeatureInProject(featureId);
    // Same cross-check the REST create does, through the guard the repository tools already use:
    // a task must not bind a repository from another project.
    scopeGuard.requireRepoInProject(repositoryId);
    Task task =
        taskService.create(
            featureId, repositoryId, title, description, dependsOnTaskId, changedBy());
    announce();
    return summarize(task);
  }

  @McpServer("repository")
  @Tool(
      name = "update_task",
      description =
          "Change a task of a REFINING epic. Omitted fields keep their current value. Refused once"
              + " the owning epic leaves REFINING. The implemented marker is not editable here —"
              + " that is recorded by people as work ships.")
  public TaskSummary updateTask(
      @ToolArg(description = "id of a task in this project") String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(
              required = false,
              description =
                  "id of another task in the same feature to depend on; omit to keep the current"
                      + " one")
          String dependsOnTaskId) {
    requireTaskInProject(id);
    Task task =
        taskService.update(
            id, title, description, dependsOnTaskId, false, null, false, changedBy());
    announce();
    return summarize(task);
  }

  @McpServer("repository")
  @Tool(
      name = "remove_task",
      description =
          "Remove a task of a REFINING epic. Refused once the owning epic leaves REFINING.")
  public String removeTask(@ToolArg(description = "id of a task in this project") String id) {
    requireTaskInProject(id);
    taskService.delete(id, changedBy());
    announce();
    return "Removed task " + id;
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * Ensures {@code epicId} names an epic of the scoped project. An epic elsewhere reads as not
   * found rather than as forbidden — the model is told nothing about what other projects hold.
   */
  private Epic requireEpicInProject(String epicId) {
    Epic epic = epicService.get(epicId);
    if (!scope.requireProjectId().equals(epic.projectId)) {
      throw new NotFoundException("Epic not found in this project: " + epicId);
    }
    return epic;
  }

  private Feature requireFeatureInProject(String featureId) {
    Feature feature = featureService.get(featureId);
    requireEpicInProject(feature.epicId);
    return feature;
  }

  private Task requireTaskInProject(String taskId) {
    Task task = taskService.get(taskId);
    requireFeatureInProject(task.featureId);
    return task;
  }

  // --- Plumbing -------------------------------------------------------------

  /** Tell the project's browsers to re-read the epic tree. */
  private void announce() {
    changePublisher.fire(scope.requireProjectId(), ProjectChangeHint.Topic.EPICS);
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  private static EpicSummary summarize(Epic epic) {
    return new EpicSummary(
        epic.id, epic.slug, epic.title, epic.status.name(), epic.description);
  }

  private static FeatureSummary summarize(Feature feature) {
    return new FeatureSummary(
        feature.id, feature.slug, feature.title, feature.description, feature.dependsOnFeatureId);
  }

  private static TaskSummary summarize(Task task) {
    return new TaskSummary(
        task.id, task.slug, task.title, task.description, task.repositoryId, task.dependsOnTaskId);
  }
}
