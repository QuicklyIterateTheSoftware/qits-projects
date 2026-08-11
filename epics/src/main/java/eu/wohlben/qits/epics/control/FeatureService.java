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
 *
 * <p>Every write here obeys the owning epic's phase ({@link EpicLifecycle}): a feature is scope, so
 * creating, deleting or structurally editing one needs a draft, while {@code implementedOn} moves
 * only once that scope is frozen.
 */
@ApplicationScoped
public class FeatureService {

  @Inject FeatureRepository featureRepository;

  @Inject EpicRepository epicRepository;

  @Inject TaskRepository taskRepository;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /**
   * The epic's features, oldest first, held through a postgres cutover ({@link ReadPatience}). The
   * caller is a plain GET with no transaction of its own; the same repository call inside this
   * module's writes (slug uniqueness, cascade delete) goes to the repository directly and stays
   * unwrapped, because a retry inside an open transaction re-runs on a rollback-only connection.
   */
  public List<Feature> listByEpic(String epicId) {
    return patience.hold("feature list", () -> featureRepository.listByEpic(epicId));
  }

  public Feature get(String id) {
    return featureRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Feature not found: " + id));
  }

  /** A new feature, held through a postgres cutover ({@link WritePatience}). */
  public Feature create(
      String epicId,
      String title,
      String description,
      String dependsOnFeatureId,
      String changedBy) {
    return writes.hold(
        "feature create",
        () -> {
          Validations.requireText(title, "title");
          EpicLifecycle.requireRefining(requireEpic(epicId));
          if (dependsOnFeatureId != null) {
            requireDependencyInEpic(dependsOnFeatureId, epicId);
          }
          Feature feature = new Feature();
          feature.id = UUID.randomUUID().toString();
          feature.epicId = epicId;
          feature.title = title;
          // Minted once, at create, and never re-derived on update — see Epic.slug.
          feature.slug =
              Slugs.unique(
                  Slugs.slugify(title, feature.id, "feature-"),
                  featureRepository.listByEpic(epicId).stream().map(f -> f.slug).toList());
          feature.description = description;
          feature.dependsOnFeatureId = dependsOnFeatureId;
          featureRepository.persist(feature);
          auditService.record(
              AuditEntityType.FEATURE,
              feature.id,
              epicId,
              AuditOperation.CREATE,
              changedBy,
              feature);
          return feature;
        });
  }

  /**
   * Partial update. A null {@code title}/{@code description} leaves that field unchanged; the
   * dependency and ship-date are changed only via their explicit value/clear flag pair.
   *
   * <p>The freeze is applied per field: whatever this call touches must be allowed by the epic's
   * phase. A call that supplies both kinds therefore always fails — no status allows both.
   *
   * <p>Held through a postgres cutover ({@link WritePatience}).
   */
  public Feature update(
      String id,
      String title,
      String description,
      String dependsOnFeatureId,
      boolean clearDependsOn,
      Instant implementedOn,
      boolean clearImplementedOn,
      String changedBy) {
    return writes.hold(
        "feature update",
        () -> {
          Feature feature = get(id);
          boolean touchesMarker = implementedOn != null || clearImplementedOn;
          // An edit that supplies nothing at all is counted as structural: it is the scope endpoint.
          boolean touchesScope =
              title != null
                  || description != null
                  || dependsOnFeatureId != null
                  || clearDependsOn
                  || !touchesMarker;
          Epic epic = requireEpic(feature.epicId);
          if (touchesScope) {
            EpicLifecycle.requireRefining(epic);
          }
          if (touchesMarker) {
            EpicLifecycle.requireImplementation(epic);
          }
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
        });
  }

  /**
   * Removes a feature, its tasks and its dependents' pointers, held through a cutover ({@link
   * WritePatience}) — one transaction, so a retry never leaves half a cascade behind.
   */
  public void delete(String id, String changedBy) {
    writes.run(
        "feature delete",
        () -> {
          Feature feature = get(id);
          String epicId = feature.epicId;
          EpicLifecycle.requireRefining(requireEpic(epicId));
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
        });
  }

  private Epic requireEpic(String epicId) {
    if (epicId == null) {
      throw new NotFoundException("Epic not found: null");
    }
    return epicRepository
        .findByIdOptional(epicId)
        .orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
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
