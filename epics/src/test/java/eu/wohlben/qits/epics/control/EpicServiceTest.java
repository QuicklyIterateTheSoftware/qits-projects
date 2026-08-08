package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EpicServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  @Test
  void createReadUpdateDelete() {
    Epic epic = epicService.create("proj-1", "Planning domain", "The spine", "alice");
    assertNotNull(epic.id);
    assertEquals("proj-1", epic.projectId);
    assertNotNull(epic.createdAt);
    assertNotNull(epic.updatedAt);

    Epic fetched = epicService.get(epic.id);
    assertEquals("Planning domain", fetched.title);

    Epic updated = epicService.update(epic.id, "Planning domain v2", "Longer spine", "bob");
    assertEquals("Planning domain v2", updated.title);
    // created_at is immutable; update bumps updated_at only.
    assertEquals(epic.createdAt, updated.createdAt);
    assertFalse(updated.updatedAt.isBefore(updated.createdAt));

    epicService.delete(epic.id, "bob");
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> epicService.get(epic.id)));
  }

  @Test
  void listByProjectScopesToTheProject() {
    epicService.create("proj-a", "A1", null, "t");
    epicService.create("proj-a", "A2", null, "t");
    epicService.create("proj-b", "B1", null, "t");

    assertEquals(2, epicService.listByProject("proj-a").size());
    assertEquals(1, epicService.listByProject("proj-b").size());
    assertTrue(epicService.listByProject("proj-none").isEmpty());
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheProject() {
    Epic first = epicService.create("proj-1", "Planning domain", null, "t");
    assertEquals("planning-domain", first.slug);

    // Same slug in the same project → the next free suffix, oldest keeps the clean one.
    Epic second = epicService.create("proj-1", "Planning   DOMAIN!", null, "t");
    assertEquals("planning-domain-2", second.slug);

    // Another project is another scope, so the clean slug is free again.
    assertEquals("planning-domain", epicService.create("proj-2", "Planning domain", null, "t").slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Epic epic = epicService.create("proj-1", "Planning domain", null, "t");
    Epic renamed = epicService.update(epic.id, "Something else entirely", null, "t");
    // The slug names a branch; a rename must not orphan the branches already cut from it.
    assertEquals("planning-domain", renamed.slug);
  }

  @Test
  void blankTitleIsRejected() {
    assertThrows(BadRequestException.class, () -> epicService.create("proj-1", "  ", null, "t"));
  }

  @Test
  void getUnknownEpicThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> epicService.get("nope"));
  }

  @Test
  void deleteCascadesToFeaturesAndTasks() {
    Epic epic = epicService.create("proj-1", "Epic", null, "t");
    var feature = featureService.create(epic.id, "Feature", null, null, "t");
    var task = taskService.create(feature.id, "repo-1", "Task", null, null, "t");

    epicService.delete(epic.id, "t");

    // The in-service cascade removed the whole subtree.
    inFreshTx(
        () -> {
          assertThrows(NotFoundException.class, () -> featureService.get(feature.id));
          assertThrows(NotFoundException.class, () -> taskService.get(task.id));
        });
  }

  @Test
  void deleteRecordsAuditForWholeSubtreeAndSurvivesDeletion() {
    Epic epic = epicService.create("proj-1", "Epic", null, "carol");
    var feature = featureService.create(epic.id, "Feature", null, null, "carol");
    var task = taskService.create(feature.id, "repo-1", "Task", null, null, "carol");

    epicService.delete(epic.id, "carol");

    // The audit log is queryable by epicId even though the live rows are gone (git replacement).
    var history = auditService.listForEpic(epic.id);
    // A DELETE row exists for the epic AND each cascaded child.
    assertTrue(
        history.stream()
            .anyMatch(
                a -> a.entityType == AuditEntityType.EPIC && a.operation == AuditOperation.DELETE));
    assertTrue(
        history.stream()
            .anyMatch(
                a ->
                    a.entityType == AuditEntityType.FEATURE
                        && a.entityId.equals(feature.id)
                        && a.operation == AuditOperation.DELETE));
    assertTrue(
        history.stream()
            .anyMatch(
                a ->
                    a.entityType == AuditEntityType.TASK
                        && a.entityId.equals(task.id)
                        && a.operation == AuditOperation.DELETE));
    history.forEach(a -> assertEquals("carol", a.changedBy));
  }

  @Test
  void mutationsAreAudited() {
    Epic epic = epicService.create("proj-1", "Epic", null, "alice");
    epicService.update(epic.id, "Epic v2", null, "bob");
    epicService.delete(epic.id, "carol");

    List<AuditOperation> ops =
        auditService.listForEntity(AuditEntityType.EPIC, epic.id).stream()
            .map(a -> a.operation)
            .toList();
    // Newest first: DELETE, UPDATE, CREATE.
    assertEquals(List.of(AuditOperation.DELETE, AuditOperation.UPDATE, AuditOperation.CREATE), ops);

    var entries = auditService.listForEntity(AuditEntityType.EPIC, epic.id);
    assertEquals("carol", entries.get(0).changedBy);
    assertEquals("bob", entries.get(1).changedBy);
    assertEquals("alice", entries.get(2).changedBy);
    entries.forEach(e -> assertNotNull(e.changedAt));
    // The CREATE snapshot carries the entity's fields as JSON.
    assertTrue(entries.get(2).snapshot.contains("\"projectId\":\"proj-1\""));
  }
}
