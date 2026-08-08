package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * The project-agent lifecycle: the ensure ladder, the stop verb, and the read behind all three REST
 * routes. Adapted from the ladder qits-workspaces runs inside {@code ensureContainer}.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li><b>Running</b> — no-op. The container is up and its {@code /workspace} is whatever the last
 *       session left there.
 *   <li><b>Present but stopped</b> — {@code docker start}. Lossless: the checkout, its submodules
 *       and any uncommitted work survive, which is the whole reason the stop policy never removes.
 *   <li><b>Absent</b> — provision: create the labelled volume, then {@code docker run}. The daemon
 *       self-clones the wrapper into the volume on boot; the host awaits none of it, because a
 *       browser opening a refinement panel should not block on a clone.
 * </ol>
 *
 * <p><b>Synchronous, and serialized per project.</b> Two concurrent ensures would race into two
 * {@code docker run}s, of which the second fails on a name already in use — a failure that reads
 * like a bug in the ladder rather than like two clicks. A per-project lock removes it, and a caller
 * that arrives while the lock is held sees {@link AgentRuntimeStatus#PROVISIONING} rather than
 * queueing behind an image pull.
 *
 * <p><b>A container is only this project's if its label says so.</b> The container name is derived
 * from the project <em>slug</em>, and {@code Project.slug} is deliberately not unique in this
 * context — two projects can be named the same thing. So every read checks {@code qits.project} and
 * refuses a foreign container with a 409 rather than adopting it, which would hand one project a
 * shell over another's checkout. The refusal names both ids, because renaming a project is the only
 * way out of it.
 */
@ApplicationScoped
public class AgentContainers {

  private static final Logger LOG = Logger.getLogger(AgentContainers.class);

  @Inject ProjectService projectService;

  @Inject ContainerRuntime runtime;

  @Inject AgentDaemonRegistry registry;

  @Inject AgentTunnels tunnels;

  /** One lock per project, so the ladder is serialized without serializing the whole service. */
  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  /**
   * Bring the project's agent container up, and answer what it is now. 404 for a project this
   * service does not have.
   */
  public AgentContainerState ensure(String projectId) {
    Project project = projectService.get(projectId); // 404s an unknown id
    String name = containerNameOf(project);
    ReentrantLock lock = locks.computeIfAbsent(projectId, id -> new ReentrantLock());
    if (!lock.tryLock()) {
      // Somebody else is already doing exactly this. Answering rather than queueing keeps a second
      // click off an image pull's critical path.
      return AgentContainerState.of(AgentRuntimeStatus.PROVISIONING);
    }
    try {
      ContainerRuntime.ContainerInfo existing = requireOwnContainer(projectId, name).orElse(null);
      if (existing == null) {
        runtime.run(projectId, project.slug, ProjectService.wrapperName(project));
      } else if (!existing.running()) {
        runtime.start(name);
      }
      // A freshly started container has said nothing yet, so it would look idle to the sweep. The
      // stamp is what buys it the full idle window to dial home in.
      registry.touch(projectId);
      return state(projectId, AgentRuntimeStatus.RUNNING);
    } catch (DomainException refused) {
      throw refused; // the ownership 409 is an answer, not a failure to report as FAILED
    } catch (RuntimeException e) {
      // The runtime could not produce a running container. FAILED is the honest answer, and the
      // reason rides the same detail field a failed provision uses rather than living only in a log
      // nobody reading the panel can see.
      LOG.errorf(e, "Could not ensure the agent container for project %s", projectId);
      return AgentContainerState.of(AgentRuntimeStatus.FAILED).failedWith(e.getMessage());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stop the project's agent container gracefully, leaving it and its checkout in place. Idempotent
   * — a project with no container, or one already stopped, is answered rather than refused.
   */
  public AgentContainerState stop(String projectId) {
    Project project = projectService.get(projectId);
    String name = containerNameOf(project);
    ContainerRuntime.ContainerInfo existing = requireOwnContainer(projectId, name).orElse(null);
    if (existing == null) {
      return AgentContainerState.absent();
    }
    if (existing.running()) {
      runtime.stop(name);
    }
    // The tunnel's loopback listener and its client belong to a container that is going away; a new
    // one is opened on the next request. The activity stamp goes with it, so a restart starts the
    // idle window afresh rather than inheriting a stale one.
    tunnels.closeTunnel(projectId);
    registry.forget(projectId);
    return AgentContainerState.of(AgentRuntimeStatus.STOPPED);
  }

  /** What the project's agent container is doing right now, without changing anything. */
  public AgentContainerState status(String projectId) {
    Project project = projectService.get(projectId);
    String name = containerNameOf(project);
    ReentrantLock lock = locks.get(projectId);
    if (lock != null && lock.isLocked()) {
      return AgentContainerState.of(AgentRuntimeStatus.PROVISIONING);
    }
    ContainerRuntime.ContainerInfo existing = requireOwnContainer(projectId, name).orElse(null);
    if (existing == null) {
      return AgentContainerState.absent();
    }
    return state(
        projectId, existing.running() ? AgentRuntimeStatus.RUNNING : AgentRuntimeStatus.STOPPED);
  }

  /** The container by this project's name, or empty; refuses one that belongs to another project. */
  private Optional<ContainerRuntime.ContainerInfo> requireOwnContainer(
      String projectId, String name) {
    ContainerRuntime.ContainerInfo info = runtime.inspect(name).orElse(null);
    if (info == null) {
      return Optional.empty();
    }
    String owner = info.projectId();
    if (owner != null && !owner.isBlank() && !owner.equals(projectId)) {
      throw new DomainException(
          409,
          "The container name '"
              + name
              + "' is already taken by project "
              + owner
              + ". Two projects share a slug; rename one of them to give each its own agent.");
    }
    return Optional.of(info);
  }

  /** The container name for a project, refusing a row that has no slug to derive one from. */
  private String containerNameOf(Project project) {
    if (project.slug == null || project.slug.isBlank()) {
      throw new DomainException(
          409,
          "Project "
              + project.id
              + " has no slug, so it has no wrapper repository and no agent container to run one"
              + " over.");
    }
    return runtime.containerName(project.slug);
  }

  /**
   * A container status plus whatever its daemon has announced about itself — and, ahead of both, a
   * provision that failed.
   *
   * <p>A container whose self-clone failed is up, healthy to docker and useless: its {@code
   * /workspace} is empty, so reporting it {@code RUNNING} sends the browser to open a terminal on
   * nothing. The last {@link eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed} outranks the
   * docker status until a {@code Provisioned} or a stop clears it.
   *
   * <p>Nothing here re-provisions — see {@link AgentContainerState#failedWith}. Recovery is to
   * remove the container and ensure it again.
   */
  private AgentContainerState state(String projectId, AgentRuntimeStatus status) {
    AgentContainerState observed =
        registry
            .lookup(projectId)
            .map(info -> new AgentContainerState(status, true, info.daemonVersion(), null))
            .orElseGet(() -> AgentContainerState.of(status));
    return registry.provisionFailure(projectId).map(observed::failedWith).orElse(observed);
  }
}
