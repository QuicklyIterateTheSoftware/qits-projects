package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The idle sweep, driven past its own deadline by a fake clock.
 *
 * <p>Plain JUnit and hand-built collaborators: the sweep's whole logic is a comparison against a
 * stamp, and booting an application to shorten one timeout would prove less and cost a minute a run.
 * {@code sweep(Instant)} takes the clock for exactly this reason.
 *
 * <p>{@code liveProjects()} is overridden rather than backed by a real {@code ProjectService},
 * which is what keeps that true — but the mapping it feeds is not stubbed out with it: the sweep
 * still has to turn a container <em>name</em> back into a project id, and
 * {@link #skipsAContainerNoLiveProjectIsNamedBy} is the case that would otherwise stop somebody
 * else's container under a project id nothing answers to.
 */
class AgentIdleSweepTest {

  private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

  private final FakeContainerRuntime runtime = new FakeContainerRuntime();
  private final AgentDaemonRegistry registry = new AgentDaemonRegistry();
  private final List<Project> projects = new ArrayList<>();

  private final AgentIdleSweep sweep =
      new AgentIdleSweep() {
        @Override
        List<Project> liveProjects() {
          return projects;
        }
      };

  AgentIdleSweepTest() {
    sweep.runtime = runtime;
    sweep.registry = registry;
    sweep.tunnels = new AgentTunnels();
    sweep.idleTimeout = Duration.ofHours(4);
  }

  /** A live project with the slug its agent container is named after. */
  private void project(String id, String slug) {
    Project project = new Project();
    project.id = id;
    project.slug = slug;
    projects.add(project);
  }

  @Test
  void stopsAContainerNobodyHasBeenHeardFromSince() {
    project("project-old", "old");
    runtime.given("project-old", "old", true);
    registry.touch("project-old", NOW.minus(Duration.ofHours(5)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of("stop:project-old"), runtime.calls());
    assertTrue(
        registry.lastActivityAt("project-old").isEmpty(),
        "the stamp goes with the container, so a restart starts the window afresh");
  }

  /**
   * Inside the window the container is left alone <em>and</em> stamped on the orchestrator's side.
   * The spec carries the same idle window as a policy, so a pass that only skipped would let that
   * belt stop a container this sweep had just decided was in use.
   */
  @Test
  void leavesAContainerInsideTheWindowAloneAndKeepsTheOtherClockInStep() {
    project("project-live", "live");
    runtime.given("project-live", "live", true);
    registry.touch("project-live", NOW.minus(Duration.ofHours(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of("touch:project-live"), runtime.calls());
  }

  @Test
  void neverTouchesAStoppedContainer() {
    project("project-down", "down");
    runtime.given("project-down", "down", false);
    registry.touch("project-down", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), runtime.calls(), "stop is not idempotence, it is a verb");
  }

  @Test
  void stampsAContainerItHasNeverHeardFromRatherThanReapingItOnSight() {
    // One that outlived a restart of this service, or whose daemon has never dialled home.
    project("project-orphan", "orphan");
    runtime.given("project-orphan", "orphan", true);

    assertEquals(0, sweep.sweep(NOW), "the first sight is a stamp, not a stop");
    assertEquals(NOW, registry.lastActivityAt("project-orphan").orElseThrow());

    assertEquals(1, sweep.sweep(NOW.plus(Duration.ofHours(5))), "and it ages out one window later");
  }

  /**
   * The container of a project that was deleted without its agent being removed. Its name resolves
   * to nothing, and every action past the stop — closing the tunnel, forgetting the registry entry —
   * is addressed by a project id there is no longer one of. It is left alone; the ensure ladder is
   * what reports it, as a 409 to whoever takes the freed slug next.
   */
  @Test
  void skipsAContainerNoLiveProjectIsNamedBy() {
    runtime.given("project-deleted", "deleted", true);
    registry.touch("project-deleted", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), runtime.calls());
  }

  @Test
  void aZeroTimeoutIsTheKillSwitch() {
    sweep.idleTimeout = Duration.ZERO;
    project("project-old", "old");
    runtime.given("project-old", "old", true);
    registry.touch("project-old", NOW.minus(Duration.ofDays(30)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), runtime.calls());
  }
}
