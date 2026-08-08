package eu.wohlben.qits.projects.agenthost;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Stops project-agent containers nobody is using.
 *
 * <p>An agent container is expensive — the whole workspace toolchain, a checkout, and a JVM's worth
 * of daemon — and it is started on the first open of a refinement panel, which means it is started
 * far more often than anyone remembers to stop it. So the harness has two ways down: the explicit
 * Stop verb, and this.
 *
 * <p><b>It stops, and never removes.</b> The container and its {@code /workspace} volume survive, so
 * the next ensure is a {@code docker start} that reattaches the same checkout with its submodules,
 * its build caches and any uncommitted work intact. Nothing here discards work, which is what makes
 * an automatic sweep safe to run at all.
 *
 * <p><b>What counts as activity</b> is anything the daemon says: its {@code Hello}, its heartbeat,
 * and every agent lifecycle report. A container whose agent is running a long silent build still
 * heartbeats, so the window measures "nobody is using this project", not "nothing is happening".
 * The host also stamps a container when it starts one, so a fresh container gets the full window to
 * dial home in rather than being reaped for having said nothing yet.
 *
 * <p><b>A container this process has never heard of is stamped on sight</b> rather than reaped or
 * made immortal — one whose daemon has never connected, or that outlived a restart of this service.
 * It then ages out normally, one window later.
 */
@ApplicationScoped
public class AgentIdleSweep {

  private static final Logger LOG = Logger.getLogger(AgentIdleSweep.class);

  @Inject ContainerRuntime runtime;

  @Inject AgentDaemonRegistry registry;

  @Inject AgentTunnels tunnels;

  /**
   * How long a project agent may go unheard-from before it is stopped. Four hours: long enough to
   * survive lunch and a long build, short enough that a morning's refinement is not still holding a
   * container that evening. A zero or negative value disables the sweep, which is the kill switch —
   * the explicit Stop verb is unaffected either way.
   */
  @ConfigProperty(name = "qits.projects.agent-idle-timeout", defaultValue = "PT4H")
  Duration idleTimeout;

  /**
   * How often the sweep runs. A floor on how late a stop is, not the timeout: a sweep that runs a
   * minute after the deadline still stops a container that became idle at the deadline.
   */
  @Scheduled(
      every = "{qits.projects.agent-idle-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepIdleAgents() {
    sweep(Instant.now());
  }

  /**
   * {@link #sweepIdleAgents()} as of a given instant; answers how many containers it stopped.
   *
   * <p>The instant is a parameter so a test can travel past the timeout with a fake clock rather
   * than sleeping for one or booting a second application to shorten it.
   */
  int sweep(Instant now) {
    if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
      return 0;
    }
    Instant deadline = now.minus(idleTimeout);
    int stopped = 0;
    for (ContainerRuntime.ContainerInfo container : runtime.listAgentContainers()) {
      if (!container.running() || container.projectId() == null || container.projectId().isBlank()) {
        continue;
      }
      String projectId = container.projectId();
      // computeIfAbsent semantics: a container this process has never heard from is stamped now and
      // considered active, so it ages out one window later instead of on sight.
      Instant lastSeen = registry.touchIfAbsent(projectId, now);
      if (lastSeen.isAfter(deadline)) {
        continue;
      }
      LOG.infof(
          "Stopping the idle agent container %s for project %s (last activity %s)",
          container.name(), projectId, lastSeen);
      runtime.stop(container.name());
      tunnels.closeTunnel(projectId);
      registry.forget(projectId);
      stopped++;
    }
    return stopped;
  }
}
