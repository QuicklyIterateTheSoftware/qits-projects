package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The idle sweep, driven past its own deadline by a fake clock.
 *
 * <p>Plain JUnit and hand-built collaborators: the sweep's whole logic is a comparison against a
 * stamp, and booting an application to shorten one timeout would prove less and cost a minute a
 * run. {@code sweep(Instant)} takes the clock for exactly this reason.
 */
class AgentIdleSweepTest {

  private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

  private final FakeContainerRuntime runtime = new FakeContainerRuntime();
  private final AgentDaemonRegistry registry = new AgentDaemonRegistry();
  private final AgentIdleSweep sweep = new AgentIdleSweep();

  AgentIdleSweepTest() {
    sweep.runtime = runtime;
    sweep.registry = registry;
    sweep.tunnels = new AgentTunnels();
    sweep.idleTimeout = Duration.ofHours(4);
  }

  @Test
  void stopsAContainerNobodyHasBeenHeardFromSince() {
    runtime.given("qits-proj-old", "project-old", true);
    registry.touch("project-old", NOW.minus(Duration.ofHours(5)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(java.util.List.of("stop:qits-proj-old"), runtime.calls());
    assertTrue(
        registry.lastActivityAt("project-old").isEmpty(),
        "the stamp goes with the container, so a restart starts the window afresh");
  }

  @Test
  void leavesAContainerInsideTheWindowAlone() {
    runtime.given("qits-proj-live", "project-live", true);
    registry.touch("project-live", NOW.minus(Duration.ofHours(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(java.util.List.of(), runtime.calls());
  }

  @Test
  void neverTouchesAStoppedContainer() {
    runtime.given("qits-proj-down", "project-down", false);
    registry.touch("project-down", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(java.util.List.of(), runtime.calls(), "stop is not idempotence, it is a verb");
  }

  @Test
  void stampsAContainerItHasNeverHeardFromRatherThanReapingItOnSight() {
    // One that outlived a restart of this service, or whose daemon has never dialled home.
    runtime.given("qits-proj-orphan", "project-orphan", true);

    assertEquals(0, sweep.sweep(NOW), "the first sight is a stamp, not a stop");
    assertEquals(NOW, registry.lastActivityAt("project-orphan").orElseThrow());

    assertEquals(1, sweep.sweep(NOW.plus(Duration.ofHours(5))), "and it ages out one window later");
  }

  @Test
  void aZeroTimeoutIsTheKillSwitch() {
    sweep.idleTimeout = Duration.ZERO;
    runtime.given("qits-proj-old", "project-old", true);
    registry.touch("project-old", NOW.minus(Duration.ofDays(30)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(java.util.List.of(), runtime.calls());
  }
}
