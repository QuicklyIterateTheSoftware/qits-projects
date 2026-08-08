package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces an {@link AgentContainer} already seeded with the cross-cutting configuration every
 * project-agent container must have — the copy of qits-workspaces' {@code
 * WorkspaceContainerFactory}. Routing all creation through {@link #forProject} makes it
 * structurally impossible to start an agent without the shared credential volume, the {@code
 * qits.*} labels, the docker-host alias, the host uid and the daemon's dial-home identity. {@link
 * DockerAgentRuntime#run} is the sole caller; it only prepends the runtime binary and {@code run}.
 *
 * <p><b>The shared volumes are shared platform-wide on purpose.</b> The credential volume and the
 * two build caches carry the same names qits-workspaces uses, so a one-time {@code claude} login
 * and a dependency downloaded by any container are seen by every other one. Diverging the names
 * here would give a project agent its own unauthenticated agent home, which is exactly the failure
 * the shared-login model exists to avoid.
 */
@ApplicationScoped
public class AgentContainerFactory {

  /** The image the per-project agent runs — the workspace toolchain plus the daemon binary. */
  @ConfigProperty(name = "qits.projects.agent-image", defaultValue = "qits/project-agent:latest")
  String image;

  /**
   * The shared Docker network every agent container joins (and qits-projects is on), so the daemon
   * can dial this service's control socket by DNS name with no host-port publishing. Created if
   * absent at startup by {@link DockerAgentRuntime}.
   */
  @ConfigProperty(name = "qits.projects.agent-network", defaultValue = "qits-net")
  String network;

  /**
   * The shared named volume holding the coding agent's home ({@code ~/.claude} — the one-time OAuth
   * login). Mounted read/write so an in-container {@code claude} can authenticate; blank disables
   * the mount. The same volume qits-workspaces mounts, deliberately.
   */
  @ConfigProperty(name = "qits.projects.claude-volume", defaultValue = "qits_shared_dot_claude")
  String claudeVolume;

  /** Where {@link #claudeVolume} mounts (and where agent launches point {@code HOME}). */
  @ConfigProperty(name = "qits.projects.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  /**
   * Shared build caches — the Maven local repo and the pnpm store — so a dependency downloaded by
   * one container is reused by all. Blank disables the mount. Mount points are fixed and Maven/pnpm
   * are pointed at them by env, because the image's {@code HOME} is the checkout and the defaults
   * would otherwise land there and never be shared.
   */
  @ConfigProperty(name = "qits.projects.maven-volume", defaultValue = "qits_shared_m2")
  String mavenVolume;

  @ConfigProperty(name = "qits.projects.pnpm-volume", defaultValue = "qits_shared_pnpm")
  String pnpmVolume;

  /**
   * Name prefix for the per-project {@code /workspace} volume — {@code prefix + projectId}. The id
   * and not the slug: the slug is what the container is named after, and a checkout must not be
   * addressed by anything softer than the row's own key.
   */
  @ConfigProperty(name = "qits.projects.agent-volume-prefix", defaultValue = "qits_project_")
  String volumePrefix;

  /**
   * The IANA timezone the container runs in ({@code TZ}). Blank/absent inherits this service's own
   * zone, so wall-clock output in the container matches the environment qits runs in. Optional
   * because SmallRye reads an empty property value as "no value" and would fail a plain String.
   */
  @ConfigProperty(name = "qits.projects.agent-timezone")
  Optional<String> timezone;

  /**
   * Hard memory cap ({@code --memory} plus {@code --memory-swap} at the same value). Without it the
   * container sees the whole host's RAM and every JVM inside sizes its default heap against that —
   * a build run during refinement can then OOM the host. Blank disables the cap; the shipped
   * default matches qits-workspaces' {@code 4g}.
   */
  @ConfigProperty(name = "qits.projects.agent-memory-limit")
  Optional<String> memoryLimit;

  /** Process/thread cap ({@code --pids-limit}, fork-bomb guard). Blank/absent disables. */
  @ConfigProperty(name = "qits.projects.agent-pids-limit")
  Optional<String> pidsLimit;

  /** CPU cap ({@code --cpus}). Blank/absent disables. */
  @ConfigProperty(name = "qits.projects.agent-cpus")
  Optional<String> cpus;

  /**
   * The address a container reaches this service at — the authority of {@code
   * QITS_PROJECTS_DAEMON_URL}. Composed here because the daemon runs in-container and cannot
   * resolve it: it dials the url it was handed, verbatim, and parses no path out of it.
   */
  @ConfigProperty(name = "qits.projects.own-host", defaultValue = "qits-projects")
  String ownHost;

  @ConfigProperty(name = "qits.projects.own-port", defaultValue = "8080")
  String ownPort;

  /**
   * The git host the daemon's boot self-clone reads from, including qits-artifacts' own {@code
   * /artifacts/git} prefix. Stated outright rather than left to the daemon's derivation, which
   * would guess a <em>different</em> service's address off this one's authority and say so in a
   * WARN.
   */
  @ConfigProperty(
      name = "qits.projects.agent-git-base",
      defaultValue = "http://qits-artifacts:8080/artifacts/git")
  String gitBase;

  /**
   * The bearer every container's {@code ProjectsApi} requires. Without it the daemon's API does not
   * bind at all — fail-closed, because an omitted env is indistinguishable from a misconfiguration.
   * One shared value with a default, so a deployment needs no configuration: it is peer
   * authentication behind a loopback bind, not a boundary. {@link ContainerProxyRoute} presents the
   * same value.
   */
  @ConfigProperty(name = "qits.projects.daemon-api-token", defaultValue = "qits-projects-daemon")
  String daemonApiToken;

  /** The loopback port the daemon's API binds, and the authority the proxy pins. */
  @ConfigProperty(name = "qits.projects.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /** The loopback port the in-container agent lifecycle hooks POST to. */
  @ConfigProperty(name = "qits.projects.daemon-hooks-port", defaultValue = "13337")
  int daemonHooksPort;

  @Inject GitIdentity gitIdentity;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /** The {@code qits.managed} value every container and volume here carries. */
  public static final String MANAGED_LABEL_VALUE = "project-agent";

  /** The {@code qits.managed} value the per-project checkout volume carries. */
  public static final String MANAGED_VOLUME_LABEL_VALUE = "project-volume";

  /** The shared credential volume name (blank when disabled) — ensured at startup by the runtime. */
  public String claudeVolume() {
    return claudeVolume;
  }

  /** The shared Maven-repo volume name (blank when disabled). */
  public String mavenVolume() {
    return mavenVolume;
  }

  /** The shared pnpm-store volume name (blank when disabled). */
  public String pnpmVolume() {
    return pnpmVolume;
  }

  /** The shared network name — the single source of truth the runtime ensures exists. */
  public String network() {
    return network;
  }

  /**
   * The deterministic container name for a project: {@code qits-proj-<slug>}.
   *
   * <p>The slug, not the id, so a human reading {@code docker ps} can tell whose agent is running.
   * {@code Project.slug} is deliberately <b>not</b> unique in this context, so the name alone does
   * not prove ownership — every read of a container therefore checks the {@code qits.project}
   * label as well, and {@link AgentContainers} refuses rather than adopting a container that
   * belongs to another project.
   */
  public String containerName(String projectSlug) {
    return "qits-proj-" + projectSlug;
  }

  /** The deterministic per-project {@code /workspace} volume name. */
  public String projectVolumeName(String projectId) {
    return volumePrefix + projectId;
  }

  /**
   * The {@code qits.*} labels a per-project {@code /workspace} volume carries — its own {@code
   * qits.managed} value (so the container filter never matches a volume) plus the project id, so a
   * dangling volume is readable and matchable to its row.
   */
  public Map<String, String> projectVolumeLabels(String projectId) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.managed", MANAGED_VOLUME_LABEL_VALUE);
    labels.put("qits.project", projectId);
    return labels;
  }

  /**
   * An {@link AgentContainer} seeded for one project: its deterministic name, the host uid, the two
   * {@code qits.*} labels the listing and the sweep read back, the {@code host.docker.internal}
   * alias Linux needs, the shared network, the git commit identity, the shared credential and
   * build-cache volumes, the per-project checkout volume, the configured resource limits, the
   * image, and the whole of {@code qits-projects-daemon}'s dial-home environment.
   *
   * <p>Every {@code QITS_PROJECTS_DAEMON_*} name below is read from the daemon repo's own
   * {@code AGENTS.md} "Environment" table, not invented here. Two of them are append-only cross-repo
   * path contracts and are composed from {@link DaemonProtocol}'s constants so the literals live in
   * one place: the control socket and the proxy base path.
   */
  public AgentContainer forProject(String projectId, String projectSlug, String repoName) {
    AgentContainer container =
        new AgentContainer()
            .name(containerName(projectSlug))
            .user(Long.toString(hostUid()))
            .label("qits.managed", MANAGED_LABEL_VALUE)
            .label("qits.project", projectId)
            // Linux needs this for host.docker.internal to resolve to the docker bridge gateway.
            .addHost("host.docker.internal:host-gateway")
            // Join the shared network so the daemon can dial this service by DNS name (no -p).
            .network(network)
            .env("TZ", timezone());

    // The dial-home url, composed here because the daemon runs in-container and cannot resolve this
    // service's address. It dials this verbatim and parses no path out of it, which is why the
    // path prefix comes from the protocol constant rather than a literal typed twice.
    container.env(
        "QITS_PROJECTS_DAEMON_URL",
        "ws://"
            + ownHost
            + ":"
            + ownPort
            + DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX
            + projectId);
    // The path ContainerProxyRoute addresses this container at. The proxy forwards a caller's path
    // untouched, so the daemon has to be TOLD which leading part of it is its own address rather
    // than deriving one by stripping a segment. Injected from ContainerProxyPath so the route and
    // the container's idea of the route cannot drift.
    container.env("QITS_PROJECTS_DAEMON_API_BASE_PATH", ContainerProxyPath.base(projectId));
    container.env("QITS_PROJECTS_DAEMON_PROJECT_ID", projectId);
    // The wrapper repository the daemon self-clones. The clone is always name-addressed
    // (<gitBase>/<projectId>/<repoName>) because a wrapper's submodule urls are relative and an
    // id-addressed root breaks every one of them, so both halves are required.
    container.env("QITS_PROJECTS_DAEMON_REPO_NAME", repoName);
    // Stated, never derived: the git host is qits-artifacts, a different service from the one the
    // control socket points at, so the daemon's own fallback would be a guess with a WARN on it.
    container.env("QITS_PROJECTS_DAEMON_GIT_BASE", gitBase);
    // The bearer the daemon's loopback API requires. Unset means the API does not bind at all.
    container.env("QITS_PROJECTS_DAEMON_API_TOKEN", daemonApiToken);
    container.env("QITS_PROJECTS_DAEMON_API_PORT", Integer.toString(daemonApiPort));
    container.env("QITS_PROJECTS_DAEMON_HOOKS_PORT", Integer.toString(daemonHooksPort));
    container.env("QITS_PROJECTS_DAEMON_CLAUDE_MOUNT", claudeMount);

    // Resource limits (opt-out): without a memory cap every JVM in the container sizes its heap
    // against the whole host's RAM.
    memoryLimit.filter(value -> !value.isBlank()).ifPresent(container::memory);
    pidsLimit.filter(value -> !value.isBlank()).ifPresent(container::pidsLimit);
    cpus.filter(value -> !value.isBlank()).ifPresent(container::cpus);

    // The commit identity as container-level env, so every git process in the container inherits it
    // regardless of cwd or .git/config — identity env beats every git config level.
    gitIdentity.envMap().forEach(container::env);

    if (claudeVolume != null && !claudeVolume.isBlank()) {
      container.volume(claudeVolume, claudeMount);
      // Point every in-container `claude` at the shared credential dir regardless of HOME: the
      // image sets HOME to the checkout, so without this a login would land there — container-local
      // and invisible to every other container.
      container.env("CLAUDE_CONFIG_DIR", claudeMount + "/.claude");
      container.env("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
    }
    if (mavenVolume != null && !mavenVolume.isBlank()) {
      container.volume(mavenVolume, MAVEN_MOUNT);
      container.env("MAVEN_OPTS", "-Dmaven.repo.local=" + MAVEN_MOUNT);
    }
    if (pnpmVolume != null && !pnpmVolume.isBlank()) {
      container.volume(pnpmVolume, PNPM_MOUNT);
      container.env("npm_config_store_dir", PNPM_MOUNT + "/store");
    }
    // The per-project checkout, on a named volume rather than the container's writable layer, so a
    // recreation reattaches it and the daemon skips its self-clone on an already-populated volume.
    container.volume(projectVolumeName(projectId), "/workspace");

    return container.image(image);
  }

  /** The configured zone, or this service's own default zone when blank. */
  private String timezone() {
    return timezone.filter(zone -> !zone.isBlank()).orElseGet(() -> ZoneId.systemDefault().getId());
  }

  /** The host uid the container runs as, so cloned files are owned by the user. */
  private long hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return ((Number) uid).longValue();
    } catch (Exception e) {
      // Fall back to a sane default; the container just won't match the host uid.
      return 1000L;
    }
  }
}
