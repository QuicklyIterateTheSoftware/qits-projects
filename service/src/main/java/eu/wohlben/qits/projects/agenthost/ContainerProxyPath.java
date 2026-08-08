package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;

/**
 * The single source of truth for the project-agent proxy's path shape: {@code
 * /projects/container/{projectId}/…}, forwarded <b>verbatim</b> to that project's in-container
 * {@code qits-projects-daemon}.
 *
 * <p><b>The literal is not spelled here.</b> It comes from {@link
 * DaemonProtocol#CONTAINER_PROXY_PATH_PREFIX} in the vendored protocol module, because it is a
 * cross-repo agreement rather than a value either side may re-derive — the daemon's AGENTS.md calls
 * it append-only, and {@code DaemonCodecTest} asserts it as a literal on both sides. This class is
 * the host-side arithmetic over it, and nothing more.
 *
 * <p><b>Why the daemon is this service's resource and not a gateway route.</b> The gateway's route
 * table is static configuration mapping one prefix to one {@code host:port}. A project agent is one
 * process per container, addressed per project and living for one container lifetime: no stable
 * address to configure, no health check to register. qits-projects owns the project row and the
 * container lifecycle, so proxying its daemon under {@code /projects} is this service serving its
 * own resource. Nothing else may reach a daemon.
 *
 * <p><b>{@code container}, not {@code daemon}.</b> {@code /projects/daemon/{projectId}} is taken by
 * the control socket, and that literal is baked into every running container as {@code
 * QITS_PROJECTS_DAEMON_URL} — the hardest path here to change. Overloading it would be the wrong
 * economy.
 */
public final class ContainerProxyPath {

  public static final String PREFIX = DaemonProtocol.CONTAINER_PROXY_PATH_PREFIX;

  private ContainerProxyPath() {}

  /**
   * The proxied base path for one project's daemon, with trailing slash — injected verbatim as
   * {@code QITS_PROJECTS_DAEMON_API_BASE_PATH} so the daemon is <em>told</em> which leading part of
   * an incoming path is its own address instead of deriving one. No hop in the chain rewrites a
   * path; see {@link ContainerProxyRoute}.
   */
  public static String base(String projectId) {
    return PREFIX + projectId + "/";
  }
}
