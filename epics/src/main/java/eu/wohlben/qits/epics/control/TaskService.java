package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import eu.wohlben.qits.epics.persistence.FeatureRepository;
import eu.wohlben.qits.epics.persistence.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD for {@link Task}. The parent {@code featureId} is validated against the epics DB (404 if
 * absent); {@code repositoryId} is stored verbatim — cross-boundary existence (and project match)
 * against {@code domain}'s {@code Repository} is validated in the {@code service} controller.
 * {@code dependsOnTaskId}, when set, must reference an existing task <em>in the same feature</em>
 * (400 otherwise), may not point at the task itself, and may not close a cycle. Updates are partial
 * (null field = leave unchanged) with explicit clear flags for the dependency and completion
 * marker. Every mutation — including dependents cleared when a depended-on task is deleted — is
 * audited.
 *
 * <p>Every write here obeys the phase of the epic above the task's feature ({@link EpicLifecycle}):
 * a task is scope, so creating, deleting or structurally editing one needs a draft, while {@code
 * implementedAt} moves only once that scope is frozen.
 */
@ApplicationScoped
public class TaskService {

  @Inject TaskRepository taskRepository;

  @Inject FeatureRepository featureRepository;

  @Inject EpicRepository epicRepository;

  @Inject AuditService auditService;

  public List<Task> listByFeature(String featureId) {
    return taskRepository.listByFeature(featureId);
  }

  public Task get(String id) {
    return taskRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Task not found: " + id));
  }

  @Transactional
  public Task create(
      String featureId,
      String repositoryId,
      String title,
      String description,
      String dependsOnTaskId,
      String changedBy) {
    Validations.requireText(title, "title");
    Validations.requireText(repositoryId, "repositoryId");
    Feature feature = requireFeature(featureId);
    EpicLifecycle.requireRefining(requireEpic(feature));
    if (dependsOnTaskId != null) {
      requireDependencyInFeature(dependsOnTaskId, featureId);
    }
    Task task = new Task();
    task.id = UUID.randomUUID().toString();
    task.featureId = featureId;
    task.repositoryId = repositoryId;
    task.title = title;
    // Minted once, at create, and never re-derived on update — see Epic.slug.
    task.slug =
        Slugs.unique(
            Slugs.slugify(title, task.id, "task-"),
            taskRepository.listByFeature(featureId).stream().map(t -> t.slug).toList());
    task.description = description;
    task.dependsOnTaskId = dependsOnTaskId;
    taskRepository.persist(task);
    auditService.record(
        AuditEntityType.TASK, task.id, feature.epicId, AuditOperation.CREATE, changedBy, task);
    return task;
  }

  /**
   * Partial update. The freeze is applied per field: whatever this call touches must be allowed by
   * the epic's phase, so a call supplying both kinds always fails — no status allows both.
   */
  @Transactional
  public Task update(
      String id,
      String title,
      String description,
      String dependsOnTaskId,
      boolean clearDependsOn,
      Instant implementedAt,
      boolean clearImplementedAt,
      String changedBy) {
    Task task = get(id);
    boolean touchesMarker = implementedAt != null || clearImplementedAt;
    // An edit that supplies nothing at all is counted as structural: it is the scope endpoint.
    boolean touchesScope =
        title != null
            || description != null
            || dependsOnTaskId != null
            || clearDependsOn
            || !touchesMarker;
    Epic epic = requireEpic(requireFeature(task.featureId));
    if (touchesScope) {
      EpicLifecycle.requireRefining(epic);
    }
    if (touchesMarker) {
      EpicLifecycle.requireImplementation(epic);
    }
    if (title != null) {
      Validations.requireText(title, "title");
      task.title = title;
    }
    if (description != null) {
      task.description = description;
    }
    if (clearDependsOn) {
      task.dependsOnTaskId = null;
    } else if (dependsOnTaskId != null) {
      if (dependsOnTaskId.equals(id)) {
        throw new BadRequestException("A task cannot depend on itself");
      }
      requireDependencyInFeature(dependsOnTaskId, task.featureId);
      requireNoCycle(id, dependsOnTaskId);
      task.dependsOnTaskId = dependsOnTaskId;
    }
    if (clearImplementedAt) {
      task.implementedAt = null;
    } else if (implementedAt != null) {
      task.implementedAt = implementedAt;
    }
    auditService.record(
        AuditEntityType.TASK, task.id, epicIdOf(task), AuditOperation.UPDATE, changedBy, task);
    return task;
  }

  @Transactional
  public void delete(String id, String changedBy) {
    Task task = get(id);
    EpicLifecycle.requireRefining(requireEpic(requireFeature(task.featureId)));
    String epicId = epicIdOf(task);
    for (Task dependent : taskRepository.listDependents(id)) {
      dependent.dependsOnTaskId = null;
      auditService.record(
          AuditEntityType.TASK,
          dependent.id,
          epicIdOf(dependent),
          AuditOperation.UPDATE,
          changedBy,
          dependent);
    }
    taskRepository.delete(task);
    auditService.record(AuditEntityType.TASK, id, epicId, AuditOperation.DELETE, changedBy, task);
  }

  private Feature requireFeature(String featureId) {
    if (featureId == null) {
      throw new NotFoundException("Feature not found: null");
    }
    return featureRepository
        .findByIdOptional(featureId)
        .orElseThrow(() -> new NotFoundException("Feature not found: " + featureId));
  }

  /** The epic above a feature — the row whose phase decides what may be written here. */
  private Epic requireEpic(Feature feature) {
    return epicRepository
        .findByIdOptional(feature.epicId)
        .orElseThrow(() -> new NotFoundException("Epic not found: " + feature.epicId));
  }

  private void requireDependencyInFeature(String taskId, String featureId) {
    Task dependency = taskRepository.findById(taskId);
    if (dependency == null || !dependency.featureId.equals(featureId)) {
      throw new BadRequestException("Unknown or out-of-feature dependsOnTaskId: " + taskId);
    }
  }

  private void requireNoCycle(String taskId, String targetId) {
    Set<String> visited = new HashSet<>();
    String cursor = targetId;
    while (cursor != null) {
      if (cursor.equals(taskId)) {
        throw new BadRequestException("dependsOnTaskId would create a dependency cycle");
      }
      if (!visited.add(cursor)) {
        break;
      }
      Task next = taskRepository.findById(cursor);
      cursor = (next == null) ? null : next.dependsOnTaskId;
    }
  }

  /** The owning epic id of a task, resolved via its feature (for audit rows). */
  private String epicIdOf(Task task) {
    Feature feature = featureRepository.findById(task.featureId);
    return feature == null ? null : feature.epicId;
  }
}
