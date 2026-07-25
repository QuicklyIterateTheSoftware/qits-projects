package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import eu.wohlben.qits.epics.persistence.FeatureRepository;
import eu.wohlben.qits.epics.persistence.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for {@link Epic}. {@code projectId} is stored verbatim — cross-boundary existence against
 * {@code domain}'s {@code Project} is validated in the {@code service} controller (this module has
 * no dependency on {@code domain}). Every mutation is recorded in the {@link AuditService audit
 * log}, including the feature/task rows removed on a cascade delete (done in-service, not via the
 * DB cascade, so each removal gets its own DELETE audit row).
 */
@ApplicationScoped
public class EpicService {

  @Inject EpicRepository epicRepository;

  @Inject FeatureRepository featureRepository;

  @Inject TaskRepository taskRepository;

  @Inject AuditService auditService;

  public List<Epic> listByProject(String projectId) {
    return epicRepository.listByProject(projectId);
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
    Epic epic = new Epic();
    epic.id = UUID.randomUUID().toString();
    epic.projectId = projectId;
    epic.title = title;
    epic.description = description;
    epicRepository.persist(epic);
    auditService.record(
        AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.CREATE, changedBy, epic);
    return epic;
  }

  @Transactional
  public Epic update(String id, String title, String description, String changedBy) {
    Epic epic = get(id);
    Validations.requireText(title, "title");
    epic.title = title;
    epic.description = description;
    auditService.record(
        AuditEntityType.EPIC, epic.id, epic.id, AuditOperation.UPDATE, changedBy, epic);
    return epic;
  }

  @Transactional
  public void delete(String id, String changedBy) {
    Epic epic = get(id);
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
}
