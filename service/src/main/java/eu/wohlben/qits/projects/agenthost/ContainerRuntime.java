package eu.wohlben.qits.projects.agenthost;

import java.util.List;
import java.util.Optional;

/**
 * The per-project agent container runtime — one container per project, holding a clone of that
 * project's wrapper repository under {@code /workspace} and running {@code qits-projects-daemon} as
 * its process. Adapted from qits-workspaces' {@code ContainerRuntime}, which stays the reference.
 *
 * <p>An interface (implemented by {@link DockerAgentRuntime}) so it stays runtime-agnostic — docker
 * today, rootless podman later — and so the suite can supply a fake and never shell a container
 * engine. That is what keeps {@code ./mvnw verify} green from a clone alone.
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <p>The workspaces interface carries {@code exec}, {@code execArgv}, {@code resolveTarget}, {@code
 * rm}, {@code restart} and {@code removeWorkspaceVolume}. None of them has a caller here:
 *
 * <ul>
 *   <li><b>No {@code exec}.</b> Nothing on the host runs a command in a project agent. The daemon
 *       owns every process in the container and is driven over its own API through the tunnel.
 *   <li><b>No {@code resolveTarget}.</b> {@code ProjectsApi} binds {@code 127.0.0.1} from
 *       capability 1, so a project agent has no address on {@code qits-net} at all — there is no
 *       direct branch for the proxy to fall back to, only the reverse tunnel.
 *   <li><b>No {@code rm} and no volume removal.</b> The stop policy is stop-never-remove: an idle
 *       sweep and the explicit Stop verb both leave the container and its {@code /workspace} clone
 *       in place, so a restart is lossless. Nothing here discards a checkout.
 * </ul>
 */
public interface ContainerRuntime {

  /** The exit code and combined stdout/stderr of a finished runtime command. */
  record ExecResult(int exitCode, String output) {}

  /**
   * A discovered project-agent container, read back from its {@code qits.*} labels. {@code running}
   * distinguishes a live container from a present-but-{@code Exited} one, because the ensure ladder
   * and the idle sweep both have to see stopped containers.
   */
  record ContainerInfo(String name, String projectId, boolean running) {}

  /** The deterministic container name for a project — no {@code inspect} round-trip needed. */
  String containerName(String projectSlug);

  /**
   * One container's name, owning project and run state, or empty when no container by that name
   * exists.
   *
   * <p>One call rather than the reference's {@code exists} + {@code isRunning} pair, because the
   * ensure ladder needs the {@code qits.project} label as well: the container name is derived from
   * the project <em>slug</em>, which this context does not make unique, so the label is what proves
   * a found container is really this project's. Three inspects would be three round-trips to learn
   * one row.
   */
  Optional<ContainerInfo> inspect(String container);

  /**
   * Creates and starts the project's container ({@code docker run -d …}, whose process is the
   * image's {@code qits-projects-daemon} ENTRYPOINT — no {@code sleep infinity} keep-alive) on the
   * shared {@code qits-net} with the {@code qits.managed=project-agent} / {@code qits.project}
   * labels the listing and the sweep read back. Returns the container name. Throws on failure.
   *
   * <p>The container publishes <b>no</b> host ports, and unlike a workspace container it needs
   * none: its only server binds loopback and is reached through the reverse tunnel.
   */
  String run(String projectId, String projectSlug, String repoName);

  /**
   * Starts a present-but-stopped container ({@code docker start}) — the lossless half of the stop
   * policy: the {@code /workspace} clone, its submodules and any uncommitted work survive, unlike a
   * re-provision that re-clones. Throws on failure.
   */
  void start(String container);

  /**
   * Gracefully stops a running container ({@code docker stop} — SIGTERM + grace) <em>without</em>
   * removing it, so a later {@link #start} is lossless. Best-effort, never throws. There is no
   * remove verb here on purpose; see the interface javadoc.
   */
  void stop(String container);

  /** Every project-agent container on this host, running or not ({@code qits.managed} filter). */
  List<ContainerInfo> listAgentContainers();

  /** The deterministic per-project {@code /workspace} volume name (prefix + {@code projectId}). */
  String projectVolumeName(String projectId);

  /**
   * Create-if-absent the per-project {@code /workspace} volume with its {@code qits.*} labels;
   * idempotent. Called by {@link #run} just before the container mounts it, so the mount always
   * attaches a labelled volume. Best-effort — a broken runtime just logs.
   *
   * <p>The volume is keyed on the project <b>id</b>, not the slug: a container name may be rebuilt
   * from a renameable-looking value, a checkout may not.
   */
  void ensureProjectVolume(String projectId);
}
