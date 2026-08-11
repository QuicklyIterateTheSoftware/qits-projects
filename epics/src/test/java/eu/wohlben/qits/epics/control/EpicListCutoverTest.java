package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The epics board's top-level read holds through a postgres cutover instead of drawing a project
 * with no epics in it.
 *
 * <p>One seam stands for the four wrapped here ({@code EpicService.listByProject}, {@code
 * FeatureService.listByEpic}, {@code TaskService.listByFeature}, {@code AuditService}'s two
 * histories): they share {@link ReadPatience}, so what this pins is the wiring — the retry fires on
 * a real cutover exception, and it stops at the configured deadline rather than forever.
 *
 * <p>The give-up half matters as much as the recovery. A retry with no floor turns a database that
 * is genuinely gone into a request that never answers, and the caller — the SPA, or an agent
 * through the MCP tool — has nothing to show for the wait either way.
 */
@QuarkusTest
@TestProfile(EpicListCutoverTest.ImpatientCutover.class)
class EpicListCutoverTest extends EpicsTestSupport {

  /**
   * Arms the failing epic table for this class alone, and shortens the patience to one second. The
   * shipped deadline is fifteen, which would make the give-up case a fifteen-second test to prove a
   * bound the code already states.
   */
  public static class ImpatientCutover implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(ConnectionLosingEpics.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.epics.read-deadline", "1S");
    }
  }

  @Inject EpicService epicService;

  @Inject ConnectionLosingEpics epics;

  @BeforeEach
  void healthy() {
    epics.loseTheConnection(0);
  }

  @Test
  void aListAnswersAfterTheReadLosesItsConnection() {
    epicService.create("proj-cutover", "Held through the cutover", null, "alice");

    epics.loseTheConnection(1);
    assertEquals(1, epicService.listByProject("proj-cutover").size());
    assertEquals(
        0, epics.unspent(), "the armed failure was never reached — the read did not go through");
  }

  /** A database that stays gone is still a failure, reported at the deadline and not before. */
  @Test
  void aDatabaseThatStaysGoneFailsAtTheDeadline() {
    epics.loseTheConnection(1_000);

    long startedAt = System.nanoTime();
    assertThrows(JDBCConnectionException.class, () -> epicService.listByProject("proj-gone"));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(elapsedMs >= 900, "gave up after " + elapsedMs + "ms — the read did not wait at all");
    assertTrue(
        elapsedMs < 10_000,
        "gave up after " + elapsedMs + "ms — the configured deadline is not the one in force");
  }
}
