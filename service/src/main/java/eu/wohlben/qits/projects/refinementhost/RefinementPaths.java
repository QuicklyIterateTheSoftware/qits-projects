package eu.wohlben.qits.projects.refinementhost;

/**
 * The path shapes of the refinement daemon surface, spelled once.
 *
 * <p><b>All three are append-only once a container exists.</b> {@link #CONTROL_SOCKET_PREFIX} is
 * baked into every refinement container as {@code QITS_WORKSPACE_DAEMON_URL} and {@link #proxyBase}
 * as {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH}; only a container recreate re-injects them, so a
 * change here strands every running refinement. Unlike the project-agent equivalents these are not
 * protocol constants — the workspace daemon dials whatever URL it is handed and is told its proxy
 * base outright, so the *shape* is this service's own choice and this class is its single home.
 *
 * <p><b>Why not reuse {@code /projects/daemon} and {@code /projects/container}.</b> Those belong to
 * the per-project agent (qits-projects-daemon, keyed by project id, its own wire protocol). A
 * refinement container runs qits-workspace-daemon, keyed by refinement row id, speaking the
 * workspace protocol — two vocabularies on one path would need the codec to sniff frames and the
 * registry to guess key types. Separate prefixes make the seam structural.
 *
 * <p>All three are outside {@code /projects/api}, so each prefix needs its entry in
 * {@code quarkus.quinoa.ignored-path-prefixes} ({@code /refinement-daemon},
 * {@code /refinement-container}) or a plain GET answers the SPA's {@code index.html}.
 */
public final class RefinementPaths {

  /** Where a refinement container's workspace daemon dials home: {@code + <rowId>}. */
  public static final String CONTROL_SOCKET_PREFIX = "/projects/refinement-daemon/";

  /**
   * Where a daemon's dial-back stream lands: {@code + <nonce>}. Shares the control socket's prefix
   * the way the agent harness's does — {@code {id}} matches exactly one segment, so no row id can
   * be {@code stream/<anything>} and the two never collide.
   */
  public static final String STREAM_PREFIX = CONTROL_SOCKET_PREFIX + "stream/";

  /** The daemon proxy's prefix: {@code + <rowId>/ + the daemon's own path, verbatim}. */
  public static final String PROXY_PREFIX = "/projects/refinement-container/";

  private RefinementPaths() {}

  /** The proxied base path of one refinement's daemon, with its trailing slash. */
  public static String proxyBase(long refinementId) {
    return PROXY_PREFIX + refinementId + "/";
  }
}
