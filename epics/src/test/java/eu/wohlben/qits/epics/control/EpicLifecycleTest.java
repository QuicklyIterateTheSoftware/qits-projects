package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.error.ConflictException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The epic lifecycle: which moves are legal, what superseding copies, and what each phase freezes.
 *
 * <p>The freeze is the part worth testing hardest — it is enforced in the services, not the UI, and
 * every case here is a rejection a client could otherwise talk the API into.
 */
@QuarkusTest
class EpicLifecycleTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  private static final Instant WHEN = Instant.parse("2026-07-25T10:15:30.00Z");

  private Epic epic() {
    return epicService.create("proj-1", "Planning domain", "The spine", "t");
  }

  private Epic frozen() {
    Epic epic = epic();
    return epicService.transition(epic.id, "IMPLEMENTATION", "t").epic();
  }

  // --- legal moves ---------------------------------------------------------------------------

  @Test
  void refiningFreezesToImplementation() {
    Epic epic = epic();
    var result = epicService.transition(epic.id, "IMPLEMENTATION", "alice");
    assertEquals(EpicStatus.IMPLEMENTATION, result.epic().status);
    assertNull(result.successor());
    assertNull(result.epic().supersededByEpicId);
  }

  @Test
  void aDraftCanBeAbandoned() {
    Epic epic = epic();
    assertEquals(
        EpicStatus.ABANDONED, epicService.transition(epic.id, "ABANDONED", "t").epic().status);
  }

  @Test
  void implementationCanBeAbandoned() {
    Epic epic = frozen();
    assertEquals(
        EpicStatus.ABANDONED, epicService.transition(epic.id, "ABANDONED", "t").epic().status);
  }

  @Test
  void transitionIsAuditedAsAnUpdate() {
    Epic epic = epic();
    epicService.transition(epic.id, "IMPLEMENTATION", "alice");

    var history = auditService.listForEntity(AuditEntityType.EPIC, epic.id);
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("alice", history.get(0).changedBy);
    assertTrue(history.get(0).snapshot.contains("\"status\":\"IMPLEMENTATION\""));
  }

  // --- marking implemented -------------------------------------------------------------------

  @Test
  void aFeaturelessEpicCanBeMarkedImplemented() {
    // The motivating case: an epic implemented straight from its description has nothing for the
    // feature derivation to fire on, and used to sit in IMPLEMENTATION forever.
    Epic epic = frozen();
    var result = epicService.transition(epic.id, "IMPLEMENTED", "alice");
    assertEquals(EpicStatus.IMPLEMENTED, result.epic().status);
    assertNull(result.successor());
  }

  @Test
  void markingImplementedStampsTheUnstampedAndKeepsEarlierTimestamps() {
    Epic epic = epic();
    Feature done = featureService.create(epic.id, "Shipped in June", null, null, "t");
    Feature open = featureService.create(epic.id, "Finished by the declaration", null, null, "t");
    Task task = taskService.create(open.id, "repo-1", "Loose end", null, null, "t");
    epicService.transition(epic.id, "IMPLEMENTATION", "t");
    java.time.Instant june = java.time.Instant.parse("2026-06-01T12:00:00Z");
    featureService.update(done.id, null, null, null, false, june, false, "t");

    epicService.transition(epic.id, "IMPLEMENTED", "alice");

    assertEquals(june, featureService.get(done.id).implementedOn, "history is not rewritten");
    assertNotNull(featureService.get(open.id).implementedOn);
    assertNotNull(taskService.get(task.id).implementedAt);
  }

  @Test
  void implementedFreezesEverythingAndMovesOnlyToSuperseded() {
    Epic epic = epic();
    Feature feature = featureService.create(epic.id, "The one feature", null, null, "t");
    epicService.transition(epic.id, "IMPLEMENTATION", "t");
    epicService.transition(epic.id, "IMPLEMENTED", "t");

    // Structural changes and marker changes are both rejected — the guards' status checks.
    assertThrows(
        ConflictException.class, () -> featureService.create(epic.id, "Late scope", null, null, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.id, null, null, null, false, null, true, "t"));
    for (String target : List.of("REFINING", "IMPLEMENTATION", "IMPLEMENTED", "ABANDONED")) {
      assertThrows(ConflictException.class, () -> epicService.transition(epic.id, target, "t"));
    }
    assertEquals(
        EpicStatus.SUPERSEDED, epicService.transition(epic.id, "SUPERSEDED", "t").epic().status);
  }

  // --- illegal moves -------------------------------------------------------------------------

  @Test
  void everyOtherMoveIsRejected() {
    Epic draft = epic();
    // A draft has no scope to supersede, and it cannot move to where it already is. Nor can it be
    // implemented without the freeze: IMPLEMENTED is reached through IMPLEMENTATION alone.
    assertThrows(
        ConflictException.class, () -> epicService.transition(draft.id, "SUPERSEDED", "t"));
    assertThrows(ConflictException.class, () -> epicService.transition(draft.id, "REFINING", "t"));
    assertThrows(
        ConflictException.class, () -> epicService.transition(draft.id, "IMPLEMENTED", "t"));

    Epic implementing = frozen();
    // Freezing is one-way: there is no route back to the draft.
    assertThrows(
        ConflictException.class, () -> epicService.transition(implementing.id, "REFINING", "t"));
    assertThrows(
        ConflictException.class,
        () -> epicService.transition(implementing.id, "IMPLEMENTATION", "t"));
  }

  @Test
  void terminalStatusesMoveNowhere() {
    Epic abandoned = epic();
    epicService.transition(abandoned.id, "ABANDONED", "t");
    for (String target :
        List.of("REFINING", "IMPLEMENTATION", "IMPLEMENTED", "SUPERSEDED", "ABANDONED")) {
      assertThrows(
          ConflictException.class, () -> epicService.transition(abandoned.id, target, "t"));
    }

    Epic superseded = frozen();
    epicService.transition(superseded.id, "SUPERSEDED", "t");
    for (String target :
        List.of("REFINING", "IMPLEMENTATION", "IMPLEMENTED", "SUPERSEDED", "ABANDONED")) {
      assertThrows(
          ConflictException.class, () -> epicService.transition(superseded.id, target, "t"));
    }
  }

  @Test
  void anUnknownTargetIsRejected() {
    Epic epic = epic();
    // The stored word is IMPLEMENTED — "DONE" names no status at all.
    assertThrows(ConflictException.class, () -> epicService.transition(epic.id, "DONE", "t"));
    assertThrows(ConflictException.class, () -> epicService.transition(epic.id, "refining", "t"));
  }

  // --- the supersede copy --------------------------------------------------------------------

  @Test
  void supersedingCopiesTheWholeScopeIntoAFreshDraft() {
    Epic old = epic();
    Feature a = featureService.create(old.id, "Feature A", "body A", null, "t");
    Feature b = featureService.create(old.id, "Feature B", null, a.id, "t");
    Task t1 = taskService.create(a.id, "repo-1", "Task one", "body 1", null, "t");
    Task t2 = taskService.create(a.id, "repo-2", "Task two", null, t1.id, "t");
    epicService.transition(old.id, "IMPLEMENTATION", "t");
    featureService.update(a.id, null, null, null, false, WHEN, false, "t");
    taskService.update(t1.id, null, null, null, false, WHEN, false, "t");

    var result = epicService.transition(old.id, "SUPERSEDED", "carol");
    Epic successor = result.successor();

    assertNotNull(successor);
    assertEquals(EpicStatus.SUPERSEDED, result.epic().status);
    assertEquals(successor.id, result.epic().supersededByEpicId);
    assertEquals(EpicStatus.REFINING, successor.status);
    assertNotEquals(old.id, successor.id);
    assertEquals(old.projectId, successor.projectId);
    assertEquals("Planning domain", successor.title);
    assertEquals("The spine", successor.description);
    // The epic's slug is unique per project and the old row still holds it, so the successor mints
    // the next free one; the copied features and tasks keep theirs (new epic, new scope).
    assertEquals("planning-domain-2", successor.slug);

    List<Feature> features = featureService.listByEpic(successor.id);
    assertEquals(2, features.size());
    Feature copyA = features.get(0);
    Feature copyB = features.get(1);
    assertEquals(List.of("feature-a", "feature-b"), features.stream().map(f -> f.slug).toList());
    assertNotEquals(a.id, copyA.id);
    assertEquals("body A", copyA.description);
    // The marker resets — nothing is implemented in a draft.
    assertNull(copyA.implementedOn);
    // The dependency points at the copy, never back at the old tree.
    assertEquals(copyA.id, copyB.dependsOnFeatureId);

    List<Task> tasks = taskService.listByFeature(copyA.id);
    assertEquals(List.of("task-one", "task-two"), tasks.stream().map(x -> x.slug).toList());
    assertEquals("repo-1", tasks.get(0).repositoryId);
    assertEquals("repo-2", tasks.get(1).repositoryId);
    assertNull(tasks.get(0).implementedAt);
    assertEquals(tasks.get(0).id, tasks.get(1).dependsOnTaskId);
    assertNotEquals(t2.id, tasks.get(1).id);

    // The old tree is untouched: it is the record of what was discarded.
    assertEquals(WHEN, featureService.get(a.id).implementedOn);
    assertEquals(2, featureService.listByEpic(old.id).size());
  }

  @Test
  void everyCopiedRowIsAuditedAsACreate() {
    Epic old = epic();
    Feature a = featureService.create(old.id, "Feature A", null, null, "t");
    taskService.create(a.id, "repo-1", "Task one", null, null, "t");
    epicService.transition(old.id, "IMPLEMENTATION", "t");

    Epic successor = epicService.transition(old.id, "SUPERSEDED", "carol").successor();

    var history = auditService.listForEpic(successor.id);
    assertEquals(3, history.size());
    assertTrue(history.stream().allMatch(e -> e.operation == AuditOperation.CREATE));
    assertTrue(history.stream().allMatch(e -> "carol".equals(e.changedBy)));
    assertEquals(
        Set.of(AuditEntityType.EPIC, AuditEntityType.FEATURE, AuditEntityType.TASK),
        history.stream().map(e -> e.entityType).collect(Collectors.toSet()));
  }

  @Test
  void supersedingAnEmptyEpicYieldsAnEmptyDraft() {
    Epic old = frozen();
    Epic successor = epicService.transition(old.id, "SUPERSEDED", "t").successor();
    assertNotNull(successor);
    assertTrue(featureService.listByEpic(successor.id).isEmpty());
  }

  // --- the freeze ----------------------------------------------------------------------------

  @Test
  void structuralChangesAreRejectedOnceTheScopeIsFrozen() {
    Epic epic = epic();
    Feature feature = featureService.create(epic.id, "Feature", null, null, "t");
    Task task = taskService.create(feature.id, "repo-1", "Task", null, null, "t");
    epicService.transition(epic.id, "IMPLEMENTATION", "t");

    assertThrows(ConflictException.class, () -> epicService.update(epic.id, "Renamed", null, "t"));
    assertThrows(
        ConflictException.class, () -> featureService.create(epic.id, "Another", null, null, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.id, "Renamed", null, null, false, null, false, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.id, null, null, null, true, null, false, "t"));
    assertThrows(ConflictException.class, () -> featureService.delete(feature.id, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.create(feature.id, "repo-1", "Another", null, null, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.update(task.id, "Renamed", null, null, false, null, false, "t"));
    assertThrows(ConflictException.class, () -> taskService.delete(task.id, "t"));
  }

  @Test
  void implementedMarkersAreRejectedWhileTheEpicIsStillADraft() {
    Epic epic = epic();
    Feature feature = featureService.create(epic.id, "Feature", null, null, "t");
    Task task = taskService.create(feature.id, "repo-1", "Task", null, null, "t");

    // Nothing ships from a draft — neither setting the marker nor clearing it.
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.id, null, null, null, false, WHEN, false, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.id, null, null, null, false, null, true, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.update(task.id, null, null, null, false, WHEN, false, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.update(task.id, null, null, null, false, null, true, "t"));
  }

  @Test
  void oneCallCannotMixScopeAndMarkers() {
    Epic epic = epic();
    Feature feature = featureService.create(epic.id, "Feature", null, null, "t");
    epicService.transition(epic.id, "IMPLEMENTATION", "t");

    // No phase allows both, so the pair is refused whichever phase the epic is in.
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.id, "Renamed", null, null, false, WHEN, false, "t"));
  }

  @Test
  void terminalEpicsRejectEveryWrite() {
    for (String target : List.of("SUPERSEDED", "ABANDONED")) {
      Epic epic = epic();
      Feature feature = featureService.create(epic.id, "Feature", null, null, "t");
      Task task = taskService.create(feature.id, "repo-1", "Task", null, null, "t");
      epicService.transition(epic.id, "IMPLEMENTATION", "t");
      epicService.transition(epic.id, target, "t");

      assertThrows(
          ConflictException.class, () -> epicService.update(epic.id, "Renamed", null, "t"));
      assertThrows(
          ConflictException.class, () -> featureService.create(epic.id, "Another", null, null, "t"));
      assertThrows(
          ConflictException.class,
          () -> featureService.update(feature.id, "Renamed", null, null, false, null, false, "t"));
      assertThrows(
          ConflictException.class,
          () -> featureService.update(feature.id, null, null, null, false, WHEN, false, "t"));
      assertThrows(ConflictException.class, () -> featureService.delete(feature.id, "t"));
      assertThrows(
          ConflictException.class,
          () -> taskService.update(task.id, null, null, null, false, WHEN, false, "t"));
      assertThrows(ConflictException.class, () -> taskService.delete(task.id, "t"));
    }
  }

  @Test
  void deletingAnEpicStaysAllowedInEveryStatus() {
    for (String target : List.of("IMPLEMENTATION", "ABANDONED")) {
      Epic epic = epic();
      featureService.create(epic.id, "Feature", null, null, "t");
      epicService.transition(epic.id, target, "t");
      // Deleting removes the row rather than editing a frozen scope; the audit log outlives it.
      epicService.delete(epic.id, "t");
      inFreshTx(() -> assertTrue(epicService.listByProject("proj-1", target).isEmpty()));
    }
  }

  // --- the status filter ---------------------------------------------------------------------

  @Test
  void listByProjectFiltersByStatus() {
    Epic draft = epic();
    Epic implementing = frozen();
    Epic abandoned = epic();
    epicService.transition(abandoned.id, "ABANDONED", "t");

    assertEquals(3, epicService.listByProject("proj-1").size());
    assertEquals(
        List.of(draft.id),
        epicService.listByProject("proj-1", "REFINING").stream().map(e -> e.id).toList());
    assertEquals(
        List.of(implementing.id),
        epicService.listByProject("proj-1", "IMPLEMENTATION").stream().map(e -> e.id).toList());
    assertTrue(epicService.listByProject("proj-1", "SUPERSEDED").isEmpty());
    // A blank filter is no filter.
    assertEquals(3, epicService.listByProject("proj-1", "  ").size());
  }
}
