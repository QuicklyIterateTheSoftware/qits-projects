package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An epics <b>write</b> holds through a postgres cutover, and lands exactly once when it does.
 *
 * <p>One seam stands for the ten wrapped in this module ({@code EpicService}'s four, {@code
 * FeatureService}'s three, {@code TaskService}'s three): they share {@link WritePatience}, so what
 * this pins is the wiring — {@code DbRetry.inNewTx} opens the transaction, a body failure that says
 * the connection went is retried, and everything else is reported on the first attempt.
 *
 * <p><b>Exactly once is the assertion that matters.</b> A retry is only worth having if a write that
 * was interrupted after being staged leaves one row and not two, and {@link FailingEpicWrites} fails
 * after {@code persist} precisely so that question has an answer. The count is read in a fresh
 * transaction, because this test calls the service on one thread with no request scope and a
 * thread-bound session would answer from its own cache.
 *
 * <p><b>The other half is the one that must NOT retry.</b> A constraint violation is as certain not
 * to have committed as a lost connection is, and as certain to fail the same way for fifteen
 * seconds; retrying it would turn one visible failure into a slow one. The proof is the attempt
 * count, not the clock: five failures armed, four still unspent.
 */
@QuarkusTest
@TestProfile(EpicWriteCutoverTest.ImpatientWrites.class)
class EpicWriteCutoverTest extends EpicsTestSupport {

  /**
   * Arms the failing epic table for this class alone, and shortens the write patience to one
   * second. The shipped deadline is fifteen, which the non-retry case would otherwise have to
   * outwait to prove it did not wait.
   */
  public static class ImpatientWrites implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(FailingEpicWrites.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.epics.write-deadline", "1S");
    }
  }

  @Inject EpicService epicService;

  @Inject FailingEpicWrites epics;

  @BeforeEach
  void healthy() {
    epics.healthy();
  }

  @Test
  void aCreateLandsExactlyOnceAfterTheWriteLosesItsConnection() {
    epics.loseTheConnection(1);

    var epic = epicService.create("proj-write-cutover", "Held through the cutover", null, "alice");

    assertEquals(
        0, epics.unspent(), "the armed failure was never reached — the write did not go through");
    inFreshTx(
        () -> {
          assertEquals(
              1,
              epicRepository.listByProject("proj-write-cutover").size(),
              "the retried create left more than one epic behind");
          assertEquals(
              1,
              auditRepository.listForEntity(AuditEntityType.EPIC, epic.id).size(),
              "the retried create left more than one audit row behind");
        });
  }

  /** A failure that is not the connection is reported at once, after exactly one attempt. */
  @Test
  void aFailureThatIsNotTheConnectionIsNotRetried() {
    epics.failWithoutLosingTheConnection(5);

    long startedAt = System.nanoTime();
    IllegalStateException reported =
        assertThrows(
            IllegalStateException.class,
            () -> epicService.create("proj-not-retried", "Reported, not retried", null, "alice"));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertEquals(FailingEpicWrites.NOT_THE_CONNECTION, reported.getMessage());
    assertEquals(4, epics.unspent(), "the write was attempted more than once");
    assertTrue(elapsedMs < 900, "waited " + elapsedMs + "ms — a non-connection failure paused");
    inFreshTx(
        () ->
            assertEquals(
                0,
                epicRepository.listByProject("proj-not-retried").size(),
                "the failed create committed a row"));
  }

  /** A cutover that does not end is still a failure, reported at the deadline and not before. */
  @Test
  void aDatabaseThatStaysGoneFailsAtTheDeadline() {
    epics.loseTheConnection(1_000);

    long startedAt = System.nanoTime();
    assertThrows(
        JDBCConnectionException.class,
        () -> epicService.create("proj-write-gone", "Never lands", null, "alice"));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(
        elapsedMs >= 900, "gave up after " + elapsedMs + "ms — the write did not wait at all");
    assertTrue(
        elapsedMs < 10_000,
        "gave up after " + elapsedMs + "ms — the configured deadline is not the one in force");
    inFreshTx(
        () ->
            assertEquals(
                0,
                epicRepository.listByProject("proj-write-gone").size(),
                "a create that never succeeded committed a row"));
  }
}
