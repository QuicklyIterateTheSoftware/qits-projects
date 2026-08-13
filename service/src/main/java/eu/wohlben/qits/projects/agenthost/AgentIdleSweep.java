package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>It stops, and never removes.</b> The {@code /workspace} volume survives whatever happens to
 * the container after it, so the next ensure reattaches the same checkout with its submodules, its
 * build caches and any uncommitted work intact. Nothing here discards work, which is what makes an
 * automatic sweep safe to run at all.
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
 *
 * <p><b>It stays host-side, and the orchestrator's identical sweep does not replace it.</b> Stopping
 * a container is the smallest part of what happens here: the tunnel's loopback listener has to be
 * closed and the daemon registry entry forgotten, and both are in-memory state of <em>this</em>
 * process that qits-containers cannot reach. The spec still carries an {@code IDLE_STOP} policy with
 * the same window, as the belt for a qits-projects that died holding a container — and this sweep
 * {@link ContainerRuntime#touch}es what it keeps, so the belt cannot fire on a container somebody is
 * working in.
 *
 * <p><b>Which project a container belongs to is resolved here now.</b> It used to be read off the
 * {@code qits.project} label; the orchestrator's listing carries no label and no ref, so what comes
 * back is container names, and a name is matched against the live projects' own
 * {@code qits-proj-<slug>}. A container matching none of them is <b>skipped</b>: it belongs to a
 * deleted project, and every action this sweep takes past the stop — the tunnel, the registry entry
 * — is addressed by a project id there is no longer one of. Nothing else here would stop it either,
 * and a container a deleted project left behind is a leak the ensure ladder reports (409) rather
 * than one a sweep should quietly act on.
 */
@ApplicationScoped
public class AgentIdleSweep {

  private static final Logger LOG = Logger.getLogger(AgentIdleSweep.class);

  @Inject ContainerRuntime runtime;

  @Inject AgentDaemonRegistry registry;

  @Inject AgentTunnels tunnels;

  /** Where a container name is resolved back to the project that derives it. */
  @Inject ProjectService projectService;

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
    Map<String, String> byContainerName = liveProjectsByContainerName();
    int stopped = 0;
    for (ContainerRuntime.ContainerInfo container : runtime.listAgentContainers()) {
      if (!container.running()) {
        continue;
      }
      String projectId = byContainerName.get(container.name());
      if (projectId == null) {
        // No live project answers to this name — see the class javadoc.
        LOG.debugf("Skipping the agent container %s: no live project is named by it", container.name());
        continue;
      }
      // computeIfAbsent semantics: a container this process has never heard from is stamped now and
      // considered active, so it ages out one window later instead of on sight.
      Instant lastSeen = registry.touchIfAbsent(projectId, now);
      if (lastSeen.isAfter(deadline)) {
        // Still wanted, so keep the orchestrator's own idle clock in step with this one.
        runtime.touch(projectId);
        continue;
      }
      LOG.infof(
          "Stopping the idle agent container %s for project %s (last activity %s)",
          container.name(), projectId, lastSeen);
      runtime.stop(projectId);
      tunnels.closeTunnel(projectId);
      registry.forget(projectId);
      stopped++;
    }
    return stopped;
  }

  /**
   * Every live project's container name to its id — the reverse of the one derivation this service
   * makes from a slug.
   *
   * <p>One read per pass rather than one call per container, and it is the projects that are
   * enumerated rather than the containers: a name that no live project derives is not this sweep's
   * to act on, and asking the orchestrator about a project's place would be an HTTP call per project
   * to learn what one listing already said.
   */
  private Map<String, String> liveProjectsByContainerName() {
    Map<String, String> byName = new HashMap<>();
    for (Project project : liveProjects()) {
      if (project.slug != null && !project.slug.isBlank()) {
        byName.put(runtime.containerName(project.slug), project.id);
      }
    }
    return byName;
  }

  /**
   * The projects a container name may belong to. One method so the suite can supply them without a
   * database, which is what keeps this test plain JUnit — the sweep's own logic is a comparison
   * against a stamp and a lookup in a map, and neither needs an application to say anything about.
   */
  List<Project> liveProjects() {
    return projectService.list();
  }
}
