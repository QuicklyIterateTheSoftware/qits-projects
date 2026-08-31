package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BuildStatusLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's own half, without a container: the wire names as literals, the decode, the status
 * word, and the poison rule. What the ledger does with a verdict is {@code CommitBuildStatusApiTest}
 * with the real database behind it.
 *
 * <p>The payloads here are hand-written JSON rather than round-tripped records, deliberately: this
 * service holds no qits-ci vocabulary jar, so the strings below ARE the contract as consumed — a
 * spelling change in qits-ci has to be a diff here, which is all a cross-repo string contract can
 * offer.
 */
class BuildStatusListenerTest {

  private BuildStatusListener listener;
  private RecordingLedger ledger;

  private static final class RecordingLedger extends BuildStatusLedger {
    final List<Verdict> recorded = new ArrayList<>();

    @Override
    public void record(Verdict verdict) {
      recorded.add(verdict);
    }
  }

  @BeforeEach
  void setUp() {
    listener = new BuildStatusListener();
    ledger = new RecordingLedger();
    listener.ledger = ledger;
  }

  private static EventFrame frame(String name, String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        name,
        Instant.parse("2026-08-30T12:00:00Z"),
        payload,
        null,
        null,
        null);
  }

  @Test
  void theWireNamesAreTheLiteralsQitsCiPublishesUnder() {
    // The wire contract, pinned as strings: a rename on either side has to be a diff here.
    assertEquals(Set.of("BuildSuccessful", "BuildFailed"), listener.signatures());
    assertEquals("projects-build-status", listener.consumerId());
  }

  @Test
  void aGreenBuildRecordsSuccessWithTheFramesOwnTime() {
    EventFrame green =
        frame(
            "BuildSuccessful",
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"finishedAt\":\"2026-08-30T12:00:00Z\","
                + "\"projectId\":\"qits\",\"repoId\":\"repo-1\",\"repoName\":\"qits-ci\","
                + "\"runId\":\"run-1\"}");
    listener.onFrame(green);

    assertEquals(1, ledger.recorded.size());
    BuildStatusLedger.Verdict verdict = ledger.recorded.get(0);
    assertEquals("run-1", verdict.runId());
    assertEquals("repo-1", verdict.repoId());
    assertEquals("qits", verdict.projectId());
    assertEquals("qits-ci", verdict.repoName());
    assertEquals("main", verdict.branch());
    assertEquals("abc123", verdict.commitSha());
    assertEquals("SUCCESS", verdict.status());
    assertEquals(green.occurredAt(), verdict.finishedAt());
    assertEquals(UUID.fromString(green.id()), verdict.causationId());
  }

  @Test
  void aRedBuildRecordsItsOwnOutcomeWord() {
    listener.onFrame(
        frame(
            "BuildFailed",
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"outcome\":\"TIMED_OUT\","
                + "\"repoId\":\"repo-1\",\"runId\":\"run-2\"}"));

    assertEquals("TIMED_OUT", ledger.recorded.get(0).status());
    assertNull(ledger.recorded.get(0).projectId(), "an id-addressed push announces no name pair");
  }

  @Test
  void aRedBuildWithNoOutcomeStillLandsAsFailedRatherThanAsPoison() {
    listener.onFrame(
        frame(
            "BuildFailed",
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"repo-1\","
                + "\"runId\":\"run-3\"}"));

    assertEquals("FAILED", ledger.recorded.get(0).status());
  }

  @Test
  void anUnreadablePayloadIsSwallowedRatherThanWedgingTheWatermark() {
    listener.onFrame(frame("BuildSuccessful", "not json at all"));

    assertTrue(ledger.recorded.isEmpty());
  }

  @Test
  void aVerdictNamingNoCoordinatesIsSkipped() {
    listener.onFrame(frame("BuildSuccessful", "{\"branch\":\"main\"}"));

    assertTrue(ledger.recorded.isEmpty());
  }

  @Test
  void aFrameIdThatIsNotAUuidCostsTheTraceEdgeAndNothingElse() {
    listener.onFrame(
        new EventFrame(
            "not-a-uuid",
            "BuildSuccessful",
            Instant.parse("2026-08-30T12:00:00Z"),
            "{\"commitSha\":\"abc123\",\"repoId\":\"repo-1\",\"runId\":\"run-4\"}",
            null,
            null,
            null));

    assertEquals(1, ledger.recorded.size());
    assertNull(ledger.recorded.get(0).causationId());
  }
}
