package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FeatureServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  private Epic epic() {
    return epicService.create("proj-1", "Epic", null, "t");
  }

  @Test
  void createUnderUnknownEpicThrowsNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> featureService.create("no-epic", "Feature", null, null, "t"));
  }

  @Test
  void blankTitleIsRejected() {
    Epic e = epic();
    assertThrows(
        BadRequestException.class, () -> featureService.create(e.id, " ", null, null, "t"));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheEpic() {
    Epic e = epic();
    Feature first = featureService.create(e.id, "Planning domain", null, null, "t");
    assertEquals("planning-domain", first.slug);

    Feature second = featureService.create(e.id, "Planning   DOMAIN!", null, null, "t");
    assertEquals("planning-domain-2", second.slug);

    // Another epic is another scope, so the clean slug is free again.
    Epic other = epic();
    assertEquals(
        "planning-domain", featureService.create(other.id, "Planning domain", null, null, "t").slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Feature feature = featureService.create(epic().id, "Planning domain", null, null, "t");
    Feature renamed =
        featureService.update(feature.id, "Renamed", null, null, false, null, false, "t");
    assertEquals("planning-domain", renamed.slug);
  }

  @Test
  void dependencyCanBeSetThenCleared() {
    Epic e = epic();
    Feature a = featureService.create(e.id, "A", null, null, "t");
    Feature b = featureService.create(e.id, "B", null, a.id, "t");
    assertEquals(a.id, b.dependsOnFeatureId);

    // Clear the dependency via the explicit clear flag.
    Feature cleared = featureService.update(b.id, null, null, null, true, null, false, "t");
    assertNull(cleared.dependsOnFeatureId);

    // Set it again by supplying a value.
    Feature reset = featureService.update(b.id, null, null, a.id, false, null, false, "t");
    assertEquals(a.id, reset.dependsOnFeatureId);
  }

  @Test
  void partialUpdateDoesNotClearOmittedFields() {
    Epic e = epic();
    Feature a = featureService.create(e.id, "A", null, null, "t");
    Feature b = featureService.create(e.id, "B", null, a.id, "t");

    // A title-only edit must not drop the dependency.
    Feature renamed = featureService.update(b.id, "B renamed", null, null, false, null, false, "t");
    assertEquals("B renamed", renamed.title);
    assertEquals(a.id, renamed.dependsOnFeatureId);

    // The ship date needs a frozen scope, and setting it must not drop title or dependency.
    epicService.transition(e.id, "IMPLEMENTATION", "t");
    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Feature shipped = featureService.update(b.id, null, null, null, false, when, false, "t");
    assertEquals("B renamed", shipped.title);
    assertEquals(a.id, shipped.dependsOnFeatureId);
    assertEquals(when, shipped.implementedOn);
  }

  @Test
  void selfDependencyIsRejected() {
    Epic e = epic();
    Feature a = featureService.create(e.id, "A", null, null, "t");
    assertThrows(
        BadRequestException.class,
        () -> featureService.update(a.id, null, null, a.id, false, null, false, "t"));
  }

  @Test
  void unknownDependencyIsRejected() {
    Epic e = epic();
    assertThrows(
        BadRequestException.class, () -> featureService.create(e.id, "A", null, "ghost", "t"));
  }

  @Test
  void crossEpicDependencyIsRejected() {
    Epic e1 = epic();
    Epic e2 = epicService.create("proj-1", "Epic2", null, "t");
    Feature inOther = featureService.create(e2.id, "Other", null, null, "t");
    assertThrows(
        BadRequestException.class, () -> featureService.create(e1.id, "A", null, inOther.id, "t"));
  }

  @Test
  void multiHopCycleIsRejected() {
    Epic e = epic();
    Feature a = featureService.create(e.id, "A", null, null, "t");
    Feature b = featureService.create(e.id, "B", null, a.id, "t"); // B -> A
    // A -> B would close the cycle A -> B -> A.
    assertThrows(
        BadRequestException.class,
        () -> featureService.update(a.id, null, null, b.id, false, null, false, "t"));
  }

  @Test
  void implementedOnTransitions() {
    Epic e = epic();
    Feature f = featureService.create(e.id, "A", null, null, "t");
    assertNull(f.implementedOn);
    // The marker only moves once the epic's scope is frozen.
    epicService.transition(e.id, "IMPLEMENTATION", "t");

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Feature shipped = featureService.update(f.id, null, null, null, false, when, false, "t");
    assertEquals(when, shipped.implementedOn);

    Feature unshipped = featureService.update(f.id, null, null, null, false, null, true, "t");
    assertNull(unshipped.implementedOn);
  }

  @Test
  void deletingADependedOnFeatureClearsAndAuditsDependents() {
    Epic e = epic();
    Feature a = featureService.create(e.id, "A", null, null, "t");
    Feature b = featureService.create(e.id, "B", null, a.id, "alice");

    featureService.delete(a.id, "carol");

    Feature reloaded = featureService.get(b.id);
    assertNull(reloaded.dependsOnFeatureId);
    // The clear is recorded as an UPDATE on the dependent, by the actor that deleted A.
    var bHistory = auditService.listForEntity(AuditEntityType.FEATURE, b.id);
    assertEquals(AuditOperation.UPDATE, bHistory.get(0).operation);
    assertEquals("carol", bHistory.get(0).changedBy);
  }

  @Test
  void deleteCascadesToTasks() {
    Epic e = epic();
    Feature f = featureService.create(e.id, "A", null, null, "t");
    var task = taskService.create(f.id, "repo-1", "T", null, null, "t");

    featureService.delete(f.id, "t");

    inFreshTx(() -> assertThrows(NotFoundException.class, () -> taskService.get(task.id)));
  }

  @Test
  void mutationsAreAudited() {
    Epic e = epic();
    Feature f = featureService.create(e.id, "A", null, null, "alice");
    var entries = auditService.listForEntity(AuditEntityType.FEATURE, f.id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
