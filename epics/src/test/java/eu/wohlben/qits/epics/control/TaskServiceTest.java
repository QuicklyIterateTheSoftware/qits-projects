package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TaskServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  private Feature feature() {
    Epic e = epicService.create("proj-1", "Epic", null, "t");
    return featureService.create(e.id, "Feature", null, null, "t");
  }

  @Test
  void createReadUpdateDelete() {
    Feature f = feature();
    Task task = taskService.create(f.id, "repo-1", "Wire it up", "body", null, "alice");
    assertNotNull(task.id);
    assertEquals("repo-1", task.repositoryId);
    assertEquals(f.id, task.featureId);

    Task fetched = taskService.get(task.id);
    assertEquals("Wire it up", fetched.title);

    Task updated =
        taskService.update(task.id, "Wire it up v2", "body2", null, false, null, false, "bob");
    assertEquals("Wire it up v2", updated.title);
    assertEquals(task.createdAt, updated.createdAt);

    taskService.delete(task.id, "bob");
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> taskService.get(task.id)));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheFeature() {
    Feature f = feature();
    Task first = taskService.create(f.id, "repo-1", "Planning domain", null, null, "t");
    assertEquals("planning-domain", first.slug);

    Task second = taskService.create(f.id, "repo-1", "Planning   DOMAIN!", null, null, "t");
    assertEquals("planning-domain-2", second.slug);

    // Another feature is another scope, so the clean slug is free again.
    Feature other = feature();
    assertEquals(
        "planning-domain",
        taskService.create(other.id, "repo-1", "Planning domain", null, null, "t").slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Task task = taskService.create(feature().id, "repo-1", "Planning domain", null, null, "t");
    Task renamed = taskService.update(task.id, "Renamed", null, null, false, null, false, "t");
    assertEquals("planning-domain", renamed.slug);
  }

  @Test
  void createUnderUnknownFeatureThrowsNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> taskService.create("no-feature", "repo-1", "T", null, null, "t"));
  }

  @Test
  void blankRepositoryIdIsRejected() {
    Feature f = feature();
    assertThrows(
        BadRequestException.class, () -> taskService.create(f.id, " ", "T", null, null, "t"));
  }

  @Test
  void dependencySetClearAndSelfCycleGuard() {
    Feature f = feature();
    Task a = taskService.create(f.id, "repo-1", "A", null, null, "t");
    Task b = taskService.create(f.id, "repo-1", "B", null, a.id, "t");
    assertEquals(a.id, b.dependsOnTaskId);

    Task cleared = taskService.update(b.id, null, null, null, true, null, false, "t");
    assertNull(cleared.dependsOnTaskId);

    // Self-dependency, unknown dependency, and multi-hop cycles are all rejected.
    assertThrows(
        BadRequestException.class,
        () -> taskService.update(a.id, null, null, a.id, false, null, false, "t"));
    assertThrows(
        BadRequestException.class,
        () -> taskService.update(a.id, null, null, "ghost", false, null, false, "t"));
    taskService.update(b.id, null, null, a.id, false, null, false, "t"); // B -> A
    assertThrows(
        BadRequestException.class,
        () ->
            taskService.update(
                a.id, null, null, b.id, false, null, false, "t")); // A -> B closes cycle
  }

  @Test
  void crossFeatureDependencyIsRejected() {
    Feature f1 = feature();
    Feature f2 = feature();
    Task inOther = taskService.create(f2.id, "repo-1", "Other", null, null, "t");
    assertThrows(
        BadRequestException.class,
        () -> taskService.create(f1.id, "repo-1", "A", null, inOther.id, "t"));
  }

  @Test
  void implementedAtTransitions() {
    Feature f = feature();
    Task t = taskService.create(f.id, "repo-1", "A", null, null, "t");
    assertNull(t.implementedAt);
    // The marker only moves once the epic's scope is frozen.
    epicService.transition(f.epicId, "IMPLEMENTATION", "t");

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Task done = taskService.update(t.id, null, null, null, false, when, false, "t");
    assertEquals(when, done.implementedAt);

    Task reopened = taskService.update(t.id, null, null, null, false, null, true, "t");
    assertNull(reopened.implementedAt);
  }

  @Test
  void mutationsAreAudited() {
    Feature f = feature();
    Task t = taskService.create(f.id, "repo-1", "A", null, null, "alice");
    var entries = auditService.listForEntity(AuditEntityType.TASK, t.id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
