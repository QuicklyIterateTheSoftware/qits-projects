package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.ConflictException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import eu.wohlben.qits.epics.persistence.FeatureRepository;
import eu.wohlben.qits.epics.persistence.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD and lifecycle for {@link Epic}. {@code projectId} is stored verbatim — cross-boundary
 * existence against {@code domain}'s {@code Project} is validated in the {@code service} controller
 * (this module has no dependency on {@code domain}). Every mutation is recorded in the {@link
 * AuditService audit log}, including the feature/task rows removed on a cascade delete (done
 * in-service, not via the DB cascade, so each removal gets its own DELETE audit row).
 *
 * <p>A new epic starts in {@link EpicStatus#REFINING}. From there {@link #transition} is the only
 * way the status moves, and {@link EpicLifecycle} is where both the legal moves and the freeze
 * rules live.
 */
@ApplicationScoped
public class EpicService {

  @Inject EpicRepository epicRepository;

  @Inject FeatureRepository featureRepository;

  @Inject TaskRepository taskRepository;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  /**
   * The outcome of a {@link #transition}: the epic in its new status, plus the successor draft when
   * the move was to {@link EpicStatus#SUPERSEDED} (null otherwise).
   */
  public record Transition(Epic epic, Epic successor) {}

  public List<Epic> listByProject(String projectId) {
    return listByProject(projectId, null);
  }

  /**
   * Epics of a project, optionally narrowed to one status. {@code status} is the enum name; a value
   * naming no status is a 400 rather than an empty list, so a typo in the filter is visible.
   *
   * <p>The read itself is held through a postgres cutover ({@link ReadPatience}): this is the
   * board's top level, and a severed connection would draw a project with no epics in it. The
   * status is parsed before the wrap, so a typo is still a 400 on the first attempt rather than a
   * question retried for fifteen seconds. Neither caller — the controller and the MCP tool — opens
   * a transaction, which is what makes the wrap legal here.
   */
  public List<Epic> listByProject(String projectId, String status) {
    if (status == null || status.isBlank()) {
      return patience.hold("epic list", () -> epicRepository.listByProject(projectId));
    }
    EpicStatus filter =
        EpicLifecycle.parse(status)
            .orElseThrow(() -> new BadRequestException("Unknown epic status: " + status));
    return patience.hold(
        "epic list by status", () -> epicRepository.listByProjectAndStatus(projectId, filter));
  }

  public Epic get(String id) {
    return epicRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Epic not found: " + id));
  }

  @Transactional
  public Epic create(String projectId, String title, String description, String changedBy) {
    Validations.requireText(projectId, "projectId");
    Validations.requireText(title, "title");
    Epic epic = insert(projectId, title, description);
    auditService.record(
        AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.CREATE, changedBy, epic);
    return epic;
  }

  @Transactional
  public Epic update(String id, String title, String description, String changedBy) {
    Epic epic = get(id);
    // Title and description are scope, so an edit needs a draft.
    EpicLifecycle.requireRefining(epic);
    Validations.requireText(title, "title");
    epic.title = title;
    epic.description = description;
    auditService.record(
        AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.UPDATE, changedBy, epic);
    return epic;
  }

  /**
   * Moves an epic to {@code target} (the enum name), rejecting a move the lifecycle does not allow
   * with a 409. Superseding also spawns the successor draft — see {@link #supersede} — and points
   * the old row at it.
   *
   * <p>A target naming no status is a 409 too: the caller asked for a phase that does not exist,
   * which is the same kind of answer as asking for one that is not reachable. An absent target is a
   * 400, because that is a malformed request rather than a refused move.
   */
  @Transactional
  public Transition transition(String id, String target, String changedBy) {
    Validations.requireText(target, "target");
    Epic epic = get(id);
    EpicStatus to =
        EpicLifecycle.parse(target)
            .orElseThrow(() -> new ConflictException("Unknown epic status: " + target));
    EpicLifecycle.requireTransition(epic.status, to);

    Epic successor = (to == EpicStatus.SUPERSEDED) ? supersede(epic, changedBy) : null;
    epic.status = to;
    if (successor != null) {
      epic.supersededByEpicId = successor.id;
    }
    auditService.record(
        AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.UPDATE, changedBy, epic);
    return new Transition(epic, successor);
  }

  @Transactional
  public void delete(String id, String changedBy) {
    Epic epic = get(id);
    // Deliberately allowed in every status: this removes the row rather than editing a frozen
    // scope, and the audit log outlives it.
    // Delete the subtree in-service (not via DB cascade) so every removed feature/task gets its own
    // DELETE audit row. Feature dependencies are epic-local (validated on write), so no other epic
    // can reference these rows — no external dependents to clear.
    for (Feature feature : featureRepository.listByEpic(id)) {
      for (Task task : taskRepository.listByFeature(feature.id)) {
        taskRepository.delete(task);
        auditService.record(
            AuditEntityType.TASK, task.id, id, AuditOperation.DELETE, changedBy, task);
      }
      featureRepository.delete(feature);
      auditService.record(
          AuditEntityType.FEATURE, feature.id, id, AuditOperation.DELETE, changedBy, feature);
    }
    epicRepository.delete(epic);
    auditService.record(AuditEntityType.EPIC, id, id, AuditOperation.DELETE, changedBy, epic);
  }

  /** A fresh epic row in {@link EpicStatus#REFINING}, unaudited — both callers audit their own. */
  private Epic insert(String projectId, String title, String description) {
    Epic epic = new Epic();
    epic.id = UUID.randomUUID().toString();
    epic.projectId = projectId;
    epic.title = title;
    // Minted once, at create, and never re-derived on update: the slug is a branch path segment,
    // and renaming an epic must not orphan the branches already cut from it.
    epic.slug =
        Slugs.unique(
            Slugs.slugify(title, epic.id, "epic-"),
            epicRepository.listByProject(projectId).stream().map(e -> e.slug).toList());
    epic.description = description;
    epic.status = EpicStatus.REFINING;
    epicRepository.persist(epic);
    return epic;
  }

  /**
   * The successor draft of a superseded epic: a new {@link EpicStatus#REFINING} epic carrying the
   * old title, description and the whole feature/task tree, so refinement restarts from what was
   * discarded rather than from a blank page.
   *
   * <p>Copies get fresh ids and keep their slugs — a feature's slug is unique within its epic and a
   * task's within its feature, and both scopes are new. The <em>epic's</em> slug is the exception:
   * its scope is the project, where the old row still holds it, so the successor mints the next
   * free one exactly as a hand-created epic would.
   *
   * <p>The implemented markers reset to null (nothing is implemented in a draft) and {@code
   * dependsOn*} is remapped to the new ids. Remapping needs the second pass: a dependency may point
   * at a sibling created after it, so the whole id map has to exist before any pointer is set.
   */
  private Epic supersede(Epic old, String changedBy) {
    Epic successor = insert(old.projectId, old.title, old.description);

    List<Feature> oldFeatures = featureRepository.listByEpic(old.id);
    List<Task> oldTasks = new ArrayList<>();
    // Keyed by the old row's id, insertion-ordered so the audit rows land in the original order.
    Map<String, Feature> featureCopies = new LinkedHashMap<>();
    Map<String, Task> taskCopies = new LinkedHashMap<>();

    for (Feature feature : oldFeatures) {
      Feature copy = new Feature();
      copy.id = UUID.randomUUID().toString();
      copy.epicId = successor.id;
      copy.title = feature.title;
      copy.slug = feature.slug;
      copy.description = feature.description;
      featureRepository.persist(copy);
      featureCopies.put(feature.id, copy);

      for (Task task : taskRepository.listByFeature(feature.id)) {
        Task taskCopy = new Task();
        taskCopy.id = UUID.randomUUID().toString();
        taskCopy.featureId = copy.id;
        taskCopy.repositoryId = task.repositoryId;
        taskCopy.title = task.title;
        taskCopy.slug = task.slug;
        taskCopy.description = task.description;
        taskRepository.persist(taskCopy);
        oldTasks.add(task);
        taskCopies.put(task.id, taskCopy);
      }
    }

    // Second pass: every copy exists now, so a pointer can be remapped whichever way it points.
    for (Feature feature : oldFeatures) {
      Feature target = featureCopies.get(feature.dependsOnFeatureId);
      featureCopies.get(feature.id).dependsOnFeatureId = (target == null) ? null : target.id;
    }
    for (Task task : oldTasks) {
      Task target = taskCopies.get(task.dependsOnTaskId);
      taskCopies.get(task.id).dependsOnTaskId = (target == null) ? null : target.id;
    }

    // Audited after the remap so each snapshot is the finished row. The first record() flushes the
    // whole batch, which is what populates the copies' creation timestamps.
    auditService.record(
        AuditEntityType.EPIC,
        successor.id,
        successor.id,
        AuditOperation.CREATE,
        changedBy,
        successor);
    for (Feature copy : featureCopies.values()) {
      auditService.record(
          AuditEntityType.FEATURE, copy.id, successor.id, AuditOperation.CREATE, changedBy, copy);
    }
    for (Task copy : taskCopies.values()) {
      auditService.record(
          AuditEntityType.TASK, copy.id, successor.id, AuditOperation.CREATE, changedBy, copy);
    }
    return successor;
  }
}
