package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The producer half of the causation chain: what a push to the git host says about why it happened.
 *
 * <p>Two claims, and the second is the one that could rot silently. The first is that the port
 * really reads the ambient cause and answers null when there is none — a push must never fail for
 * want of a cause. The second is that {@code qits-projects-gitmirror}, which has no dependencies at
 * all and therefore spells the header name as a literal, still spells the <b>same</b> name the bus
 * library defines. Nothing else connects the two strings, and a mismatch would cost every push its
 * causation edge with a green build either side.
 */
class EventstreamPushCausationTest {

  private final EventstreamPushCausation causation = new EventstreamPushCausation();

  @Test
  void theHeaderTheMirrorStampsIsTheHeaderTheBusDefines() {
    assertEquals(CausationHeader.NAME, RepoMirror.CAUSATION_HEADER);
  }

  @Test
  void anAmbientCauseBecomesTheIdAPushCarries() {
    UUID cause = UUID.randomUUID();

    CausationScope.with(cause, () -> assertEquals(cause.toString(), causation.currentCauseId()));
  }

  @Test
  void noAmbientCauseIsNoIdRatherThanAnInventedOne() {
    assertNull(causation.currentCauseId());
    CausationScope.with(null, () -> assertNull(causation.currentCauseId()));
  }
}
