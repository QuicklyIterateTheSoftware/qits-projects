package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
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
 * CRUD for {@link Feature}. The parent {@code epicId} is validated against the epics DB (404 if
 * absent); {@code dependsOnFeatureId}, when set, must reference an existing feature <em>in the same
 * epic</em> (400 otherwise), may not point at the feature itself, and may not close a dependency
 * cycle. Updates are partial (null field = leave unchanged); the two nullable relations use
 * explicit clear flags so a partial edit can't silently drop a dependency or ship date. Every
 * mutation — including the tasks removed on cascade delete and the dependents cleared when a
 * depended-on feature is deleted — is recorded in the {@link AuditService audit log}.
 */
@ApplicationScoped
public class FeatureService {

  @Inject FeatureRepository featureRepository;

  @Inject EpicRepository epicRepository;

  @Inject TaskRepository taskRepository;

  @Inject AuditService auditService;

  public List<Feature> listByEpic(String epicId) {
    return featureRepository.listByEpic(epicId);
  }

  public Feature get(String id) {
    return featureRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Feature not found: " + id));
  }

  @Transactional
  public Feature create(
      String epicId,
      String title,
      String description,
      String dependsOnFeatureId,
      String changedBy) {
    Validations.requireText(title, "title");
    requireEpic(epicId);
    if (dependsOnFeatureId != null) {
      requireDependencyInEpic(dependsOnFeatureId, epicId);
    }
    Feature feature = new Feature();
    feature.id = UUID.randomUUID().toString();
    feature.epicId = epicId;
    feature.title = title;
    feature.description = description;
    feature.dependsOnFeatureId = dependsOnFeatureId;
    featureRepository.persist(feature);
    auditService.record(
        AuditEntityType.FEATURE, feature.id, epicId, AuditOperation.CREATE, changedBy, feature);
    return feature;
  }

  /**
   * Partial update. A null {@code title}/{@code description} leaves that field unchanged; the
   * dependency and ship-date are changed only via their explicit value/clear flag pair.
   */
  @Transactional
  public Feature update(
      String id,
      String title,
      String description,
      String dependsOnFeatureId,
      boolean clearDependsOn,
      Instant implementedOn,
      boolean clearImplementedOn,
      String changedBy) {
    Feature feature = get(id);
    if (title != null) {
      Validations.requireText(title, "title");
      feature.title = title;
    }
    if (description != null) {
      feature.description = description;
    }
    if (clearDependsOn) {
      feature.dependsOnFeatureId = null;
    } else if (dependsOnFeatureId != null) {
      if (dependsOnFeatureId.equals(id)) {
        throw new BadRequestException("A feature cannot depend on itself");
      }
      requireDependencyInEpic(dependsOnFeatureId, feature.epicId);
      requireNoCycle(id, dependsOnFeatureId);
      feature.dependsOnFeatureId = dependsOnFeatureId;
    }
    if (clearImplementedOn) {
      feature.implementedOn = null;
    } else if (implementedOn != null) {
      feature.implementedOn = implementedOn;
    }
    auditService.record(
        AuditEntityType.FEATURE,
        feature.id,
        feature.epicId,
        AuditOperation.UPDATE,
        changedBy,
        feature);
    return feature;
  }

  @Transactional
  public void delete(String id, String changedBy) {
    Feature feature = get(id);
    String epicId = feature.epicId;
    // Clear same-epic dependents' pointer in-service (audited) rather than leaning on the DB
    // SET NULL, which would leave no trace.
    for (Feature dependent : featureRepository.listDependents(id)) {
      dependent.dependsOnFeatureId = null;
      auditService.record(
          AuditEntityType.FEATURE,
          dependent.id,
          dependent.epicId,
          AuditOperation.UPDATE,
          changedBy,
          dependent);
    }
    // Delete child tasks in-service so each gets a DELETE audit row.
    for (Task task : taskRepository.listByFeature(id)) {
      taskRepository.delete(task);
      auditService.record(
          AuditEntityType.TASK, task.id, epicId, AuditOperation.DELETE, changedBy, task);
    }
    featureRepository.delete(feature);
    auditService.record(
        AuditEntityType.FEATURE, id, epicId, AuditOperation.DELETE, changedBy, feature);
  }

  private void requireEpic(String epicId) {
    if (epicId == null || epicRepository.findByIdOptional(epicId).isEmpty()) {
      throw new NotFoundException("Epic not found: " + epicId);
    }
  }

  private void requireDependencyInEpic(String featureId, String epicId) {
    Feature dependency = featureRepository.findById(featureId);
    if (dependency == null || !dependency.epicId.equals(epicId)) {
      throw new BadRequestException("Unknown or out-of-epic dependsOnFeatureId: " + featureId);
    }
  }

  /**
   * Rejects a dependency edge that would close a cycle by walking the target's dependency chain.
   */
  private void requireNoCycle(String featureId, String targetId) {
    Set<String> visited = new HashSet<>();
    String cursor = targetId;
    while (cursor != null) {
      if (cursor.equals(featureId)) {
        throw new BadRequestException("dependsOnFeatureId would create a dependency cycle");
      }
      if (!visited.add(cursor)) {
        break; // pre-existing cycle elsewhere in the chain — stop rather than loop forever
      }
      Feature next = featureRepository.findById(cursor);
      cursor = (next == null) ? null : next.dependsOnFeatureId;
    }
  }
}
