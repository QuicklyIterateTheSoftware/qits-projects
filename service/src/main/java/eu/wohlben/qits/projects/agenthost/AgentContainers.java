package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 *   <li><b>Running</b> — no-op, plus a stamp. The container is up and its {@code /workspace} is
 *       whatever the last session left there.
 *   <li><b>Present but not running</b> — {@link ContainerRuntime#restart}. Lossless: the checkout,
 *       its submodules and any uncommitted work live on a named volume nothing here removes, which
 *       is the whole reason the stop policy never discards one.
 *   <li><b>Absent</b> — provision. The daemon self-clones the wrapper into the volume on boot; the
 *       host awaits none of it, because a browser opening a refinement panel should not block on a
 *       clone.
 * </ol>
 *
 * <p><b>Two arms, not three, once you look at what they send.</b> Both the second and the third are
 * one {@code ensure} to qits-containers; what differs is that the second asks for the container to
 * be replaced and the third does not. There is no start verb on that service's wire, and
 * {@link ContainerRuntime#restart} carries the whole argument.
 *
 * <p><b>Synchronous, and serialized per project.</b> Two concurrent ensures would race into two
 * bring-ups of the same place, and the second would either replace what the first had just started
 * or be refused on the name — a failure that reads like a bug in the ladder rather than like two
 * clicks. A per-project lock removes it, and a caller that arrives while the lock is held sees
 * {@link AgentRuntimeStatus#PROVISIONING} rather than queueing behind an image pull.
 *
 * <p><b>Ownership is the registry's now, not a label's.</b> A place is addressed
 * {@code owner/project-agent/<projectId>}, so a row found under this project's id <em>is</em> this
 * project's and no read has to prove it. What the label used to catch survives one arm down: a
 * project deleted without its agent being removed leaves the container <em>name</em> — derived from
 * a slug, which is unique among live projects only — sitting there for whoever takes the freed slug
 * next, and the provisioning arm refuses that with a 409 rather than adopting it.
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
    requireSlug(project);
    ReentrantLock lock = locks.computeIfAbsent(projectId, id -> new ReentrantLock());
    if (!lock.tryLock()) {
      // Somebody else is already doing exactly this. Answering rather than queueing keeps a second
      // click off an image pull's critical path.
      return AgentContainerState.of(AgentRuntimeStatus.PROVISIONING);
    }
    try {
      ContainerRuntime.ContainerInfo existing = runtime.inspect(projectId).orElse(null);
      String wrapper = ProjectService.wrapperName(project);
      if (existing == null) {
        runtime.run(projectId, project.slug, wrapper);
      } else if (!existing.running()) {
        runtime.restart(projectId, project.slug, wrapper);
      } else {
        // Up already: tell the orchestrator its own idle clock that this place is still wanted, so
        // its belt cannot stop a container somebody is working in.
        runtime.touch(projectId);
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
    requireSlug(project);
    ContainerRuntime.ContainerInfo existing = runtime.inspect(projectId).orElse(null);
    if (existing == null) {
      return AgentContainerState.absent();
    }
    if (existing.running()) {
      runtime.stop(projectId);
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
    requireSlug(project);
    ReentrantLock lock = locks.get(projectId);
    if (lock != null && lock.isLocked()) {
      return AgentContainerState.of(AgentRuntimeStatus.PROVISIONING);
    }
    ContainerRuntime.ContainerInfo existing = runtime.inspect(projectId).orElse(null);
    if (existing == null) {
      return AgentContainerState.absent();
    }
    return state(
        projectId, existing.running() ? AgentRuntimeStatus.RUNNING : AgentRuntimeStatus.STOPPED);
  }

  /**
   * Refuse a project that has no slug to name a container after.
   *
   * <p>It is a check rather than a derivation now: the place is addressed by the project id, so the
   * slug is only the container's human-readable name — but a project with no slug has no wrapper
   * repository either, so there is nothing for an agent to run over.
   */
  private static void requireSlug(Project project) {
    if (project.slug == null || project.slug.isBlank()) {
      throw new DomainException(
          409,
          "Project "
              + project.id
              + " has no slug, so it has no wrapper repository and no agent container to run one"
              + " over.");
    }
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
