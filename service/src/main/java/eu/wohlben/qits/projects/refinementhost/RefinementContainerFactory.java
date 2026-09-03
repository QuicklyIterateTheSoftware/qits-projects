package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.PullPolicy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.SharedMount;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeMount;
import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projects.entity.Refinement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.ZoneId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The whole of what a refinement container is started with — the refinement twin of
 * {@code agenthost/AgentContainerFactory}, assembling the <b>workspace image and daemon,
 * unchanged</b>: the daemon dials home to whatever {@code QITS_WORKSPACE_DAEMON_URL} names, and
 * every name below is read from qits-workspace-daemon's own environment table, not invented here.
 * The reference for the values is qits-workspaces' {@code WorkspaceContainerFactory}, whose spec a
 * refinement matches except where refinement genuinely differs:
 *
 * <ul>
 *   <li><b>No bootstrap, no services.</b> {@code BOOTSTRAP_AUTORUN=false} and {@code
 *       SERVICES_AUTOSTART=false} — a refinement runs no code, which is why the Services and
 *       Actions tabs do not exist on its route — and no {@code SERVICE_PROXY_BASE}, so a
 *       web-viewable spawn (which never happens) would WARN rather than guess.
 *   <li><b>The dial-home addresses are this service's</b>: the control socket, the proxy base and
 *       the auth audience all name qits-projects.
 *   <li><b>One extra MCP server beside {@code repository}</b>: observability, the same pair a
 *       repository-scope launch wires in a workspace. Actions is deliberately absent — there is no
 *       actions surface on this route and the daemon fails an ACTIONS launch saying so.
 * </ul>
 *
 * <p>{@code Recreate.ifChanged} on <b>both</b> arms, the workspaces reading: an unchanged spec is a
 * start in place, and a workspace-image bump landing while the refinement slept is applied at wake
 * — the one moment it can be without taking a container away from somebody working in it. That is
 * also why the commissioned credential must come off the row byte for byte
 * ({@link RefinementCommissions}).
 */
@ApplicationScoped
public class RefinementContainerFactory {

  private static final Logger LOG = Logger.getLogger(RefinementContainerFactory.class);

  /**
   * The registry host and path of the image — the workspace toolchain plus the workspace daemon,
   * exactly what a qits-workspaces workspace runs. Fully qualified for the same reason the agent
   * image is: a bare name resolves against whatever is lying in the host daemon's image store.
   */
  @ConfigProperty(name = "qits.projects.refinement-image-repo")
  String imageRepo;

  /**
   * The released calver the workspace image is pinned to. Read per container start, so a deployment
   * that changes it needs no rebuild of this service.
   *
   * <p>The deployed value rides qits-configuration's image pin: it consumes qits-workspace-daemon's
   * {@code SoftwareRelease} of {@code qits/workspace} and writes it as this application's {@code
   * QITS_PROJECTS_REFINEMENT_IMAGE_VERSION} extra, which SmallRye maps onto this key and lets win.
   * The committed property is the clone-alone default — it was what
   * {@code .config/qits/ci-event-upstream-workspace-daemon.yml} rewrote before that hop was retired.
   */
  @ConfigProperty(name = "qits.projects.refinement-image-version")
  String imageVersion;

  String image() {
    return imageRepo + ":" + imageVersion;
  }

  /** The same shared network, credential volume and build caches the agent factory mounts. */
  @ConfigProperty(name = "qits.projects.agent-network", defaultValue = "qits-net")
  String network;

  @ConfigProperty(name = "qits.projects.claude-volume", defaultValue = "qits_shared_dot_claude")
  String claudeVolume;

  @ConfigProperty(name = "qits.projects.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  @ConfigProperty(name = "qits.projects.maven-volume", defaultValue = "qits_shared_m2")
  String mavenVolume;

  @ConfigProperty(name = "qits.projects.pnpm-volume", defaultValue = "qits_shared_pnpm")
  String pnpmVolume;

  /** Name prefix for the per-refinement {@code /workspace} volume — {@code prefix + rowId}. */
  @ConfigProperty(name = "qits.projects.refinement-volume-prefix", defaultValue = "qits_refinement_")
  String volumePrefix;

  @ConfigProperty(name = "qits.projects.agent-timezone")
  Optional<String> timezone;

  @ConfigProperty(name = "qits.projects.refinement-memory-limit", defaultValue = "4g")
  Optional<String> memoryLimit;

  @ConfigProperty(name = "qits.projects.refinement-pids-limit")
  Optional<String> pidsLimit;

  @ConfigProperty(name = "qits.projects.refinement-cpus")
  Optional<String> cpus;

  /** Reaped under host memory pressure with the agents (800), before workspaces (600). */
  @ConfigProperty(name = "qits.projects.refinement-oom-score-adj", defaultValue = "800")
  Integer oomScoreAdj;

  @ConfigProperty(name = "qits.projects.own-host", defaultValue = "qits-projects")
  String ownHost;

  @ConfigProperty(name = "qits.projects.own-port", defaultValue = "8080")
  String ownPort;

  @ConfigProperty(name = "quarkus.oidc-client.auth-server-url")
  String idpAuthServerUrl;

  /** This service is also the audience protecting its refinement control socket. */
  @ConfigProperty(name = "quarkus.oidc-client.client-id")
  String platformClientId;

  /** Audience the container's git credential helper requests for its git reads. */
  @ConfigProperty(
      name = "quarkus.oidc-client.githost.grant-options.client.audience",
      defaultValue = "qits-githost")
  String gitHostAudience;

  /**
   * The address a refinement container reaches git at — the same authority the deployed
   * qits-workspaces injects into every workspace container, whose oauth2 transport rewrites the
   * image's Basic credential helper into a Bearer. The daemon's clone base is this plus
   * {@code /git}; {@code QITS_GIT_AUTH_HOST} is its authority. The shipped default is the platform's
   * own spelling and {@code application.properties} carries the measurement behind it.
   */
  @ConfigProperty(
      name = "qits.projects.refinement-git-url",
      defaultValue = "http://githost.dev.internal:8080")
  String containerGitUrl;

  /** The observability MCP server a repository-scope launch attaches beside {@code repository}. */
  @ConfigProperty(
      name = "qits.projects.refinement-observability-mcp-url",
      defaultValue = "http://dev-qits-observability:8080/observability/mcp")
  String observabilityMcpUrl;

  /** The one other MCP server: this service's own {@code repository} server, the epic tools. */
  @ConfigProperty(name = "qits.projects.agent-mcp-url")
  Optional<String> agentMcpUrl;

  /**
   * The package registries a refinement's checkout builds against, blank meaning "inject nothing" —
   * the same three keys, same names in the container, and same deliberately-absent defaults as
   * qits-workspaces (the artifacts alias carries the environment name, so a default here would be a
   * guess at the deployment's topology).
   */
  @ConfigProperty(name = "qits.projects.refinement-maven-repository-url")
  Optional<String> mavenRepositoryUrl;

  @ConfigProperty(name = "qits.projects.refinement-npm-registry-url")
  Optional<String> npmRegistryUrl;

  @ConfigProperty(name = "qits.projects.refinement-npm-proxy-url")
  Optional<String> npmProxyUrl;

  /** The bearer the daemon's loopback API requires; {@link RefinementProxyRoute} presents it. */
  @ConfigProperty(
      name = "qits.projects.refinement-daemon-api-token",
      defaultValue = "qits-projects-refinement-daemon")
  String daemonApiToken;

  @Inject GitIdentity gitIdentity;

  @Inject RefinementCommissions commissions;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /** The {@code qits.managed} hint on every refinement container. Selects nothing. */
  public static final String MANAGED_LABEL_VALUE = "refinement";

  /**
   * The deterministic container name: {@code qits-ref-<projectSlug>-<epicSlug>}, truncated to
   * docker's practical bound. A {@code docker ps} hint travelling as {@code explicitName} — the
   * address is {@code owner/refinement/<rowId>}, and the provisioning arm answers a name collision
   * with a 409.
   */
  public String containerName(String projectSlug, String epicSlug) {
    String name = "qits-ref-" + projectSlug + "-" + epicSlug;
    return name.length() <= 63 ? name : name.substring(0, 63);
  }

  /** The deterministic per-refinement {@code /workspace} volume name. */
  public String volumeName(long refinementId) {
    return volumePrefix + refinementId;
  }

  /** The fresh arm — commissions a credential of the container's own. */
  public EnsureRequest forFreshContainer(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    return request(
        refinement, projectSlug, epicSlug, wrapperName, commissions.forFreshContainer(refinement));
  }

  /** The wake arm — reads the row's pair back and sends it unchanged. */
  public EnsureRequest forExistingContainer(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    return request(
        refinement,
        projectSlug,
        epicSlug,
        wrapperName,
        commissions.forExistingContainer(refinement));
  }

  private EnsureRequest request(
      Refinement refinement,
      String projectSlug,
      String epicSlug,
      String wrapperName,
      Optional<RefinementCredentials.Commissioned> credential) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("TZ", timezone());
    // The dial-home url, dialled verbatim; the daemon parses no path out of it.
    env.put(
        "QITS_WORKSPACE_DAEMON_URL",
        "ws://" + ownHost + ":" + ownPort + RefinementPaths.CONTROL_SOCKET_PREFIX + refinement.id);
    // The path RefinementProxyRoute addresses this container at. The proxy forwards a caller's
    // path untouched, so the daemon is TOLD which leading part is its own address.
    env.put("QITS_WORKSPACE_DAEMON_API_BASE_PATH", RefinementPaths.proxyBase(refinement.id));
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", refinement.label);
    env.put("QITS_WORKSPACE_DAEMON_REPOSITORY_ID", refinement.repositoryId);
    env.put("QITS_WORKSPACE_DAEMON_BRANCH", refinement.branch);
    env.put("QITS_WORKSPACE_DAEMON_PARENT", refinement.parent);
    // Both halves of the name-addressed clone: relative submodule urls resolve only against
    // <gitBase>/<projectId>/<repoName>.
    env.put("QITS_WORKSPACE_DAEMON_PROJECT_ID", refinement.projectId);
    env.put("QITS_WORKSPACE_DAEMON_REPO_NAME", wrapperName);
    env.put("QITS_WORKSPACE_DAEMON_GIT_BASE_URL", trimSlash(containerGitUrl) + "/git");
    // A refinement runs no code: no bootstrap chain (the daemon then emits a benign ok so nothing
    // ever awaits one), and no service autostart. Deliberate, and load-bearing for the tab set.
    env.put("QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN", "false");
    env.put("QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART", "false");
    env.put("QITS_WORKSPACE_DAEMON_API_TOKEN", daemonApiToken);
    // The two MCP servers a refinement launch may attach: this service's repository server (the
    // epic tools — the reason the container exists) and observability. No actions server, on
    // purpose: there is no actions surface on this route.
    env.put("QITS_REPOSITORY_MCP_URL", repositoryMcpUrl());
    env.put("QITS_OBSERVABILITY_MCP_URL", observabilityMcpUrl);
    credential.ifPresent(
        pair -> {
          env.put("QITS_COMMISSIONED_CLIENT_ID", pair.clientId());
          env.put("QITS_COMMISSIONED_CLIENT_SECRET", pair.secret());
          // The workspace image's git credential helper: Basic against the edge, exchanged for a
          // bearer with the githost audience. The helper reads these three names.
          env.put("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
          env.put("QITS_GIT_AUTH_HOST", authority(containerGitUrl));
          env.put("QITS_GIT_AUTH_TOKEN_URL", trimSlash(idpAuthServerUrl) + "/token");
          env.put("QITS_GIT_AUTH_AUDIENCE", gitHostAudience);
          // The daemon's own dial-home bearer, presented on every control-socket handshake.
          env.put("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", trimSlash(idpAuthServerUrl) + "/token");
          env.put("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", platformClientId);
        });
    gitIdentity.envMap().forEach(env::put);

    List<SharedMount> shared = new ArrayList<>();
    if (claudeVolume != null && !claudeVolume.isBlank()) {
      shared.add(new SharedMount(claudeVolume, claudeMount));
      // The daemon's key for the shared credential home carries the qits.workspace. prefix, not
      // the daemon prefix — its own table says so.
      env.put("QITS_WORKSPACE_CLAUDE_MOUNT", claudeMount);
      env.put("CLAUDE_CONFIG_DIR", claudeMount + "/.claude");
      env.put("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
    }
    if (mavenVolume != null && !mavenVolume.isBlank()) {
      shared.add(new SharedMount(mavenVolume, MAVEN_MOUNT));
      env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + MAVEN_MOUNT);
    }
    if (pnpmVolume != null && !pnpmVolume.isBlank()) {
      shared.add(new SharedMount(pnpmVolume, PNPM_MOUNT));
      env.put("npm_config_store_dir", PNPM_MOUNT + "/store");
    }
    // The registries, when the deployment names them. Same env names as a workspace, including the
    // POSIX-shaped stand-in for the scoped-registry key (the image shim spells the @qits scope).
    mavenRepositoryUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> env.put("QITS_MAVEN_REPOSITORY_URL", url));
    npmProxyUrl.filter(url -> !url.isBlank()).ifPresent(url -> env.put("npm_config_registry", url));
    npmRegistryUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> env.put("QITS_WORKSPACE_NPM_REGISTRY_URL", url));

    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.managed", MANAGED_LABEL_VALUE);
    labels.put("qits.project", refinement.projectId);
    labels.put("qits.epic", refinement.epicId);

    String memory = memoryLimit.filter(value -> !value.isBlank()).orElse(null);
    Spec spec =
        new Spec(
            image(),
            // No entrypoint and no command: the container runs qits-workspace-daemon via the image
            // ENTRYPOINT, and a container that cannot run it must fail rather than linger.
            null,
            null,
            env,
            labels,
            network,
            null,
            List.of("host.docker.internal:host-gateway"),
            // The checkout, on a named volume: a recreate reattaches it, the daemon skips its
            // self-clone on a populated /workspace, and uncommitted work survives an image bump.
            List.of(new VolumeMount(volumeName(refinement.id), "/workspace")),
            shared,
            // No docker socket, ever: a refinement runs no code and publishes nothing.
            false,
            new Security(
                false,
                false,
                memory,
                memory,
                pids(),
                cpus.filter(v -> !v.isBlank()).orElse(null),
                oomScoreAdj),
            PullPolicy.MISSING,
            containerName(projectSlug, epicSlug),
            Long.toString(hostUid()),
            // tini at PID 1, so a long-lived container spawning agents collects no zombies.
            true);
    // EXPLICIT lifetime + ifChanged: stoppable only by its verbs, its volume is what it comes back
    // to, and an image bump is applied at wake — the workspaces contract, carried over.
    return new EnsureRequest(spec, Policy.explicitLifetime(), Recreate.ifChanged);
  }

  private String repositoryMcpUrl() {
    return agentMcpUrl
        .filter(url -> !url.isBlank())
        .orElseGet(() -> "http://" + ownHost + ":" + ownPort + "/projects/mcp");
  }

  private Long pids() {
    String value = pidsLimit.filter(text -> !text.isBlank()).orElse(null);
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException e) {
      LOG.warnf(
          "qits.projects.refinement-pids-limit is not a number ('%s'); no pids cap is set", value);
      return null;
    }
  }

  private String timezone() {
    return timezone.filter(zone -> !zone.isBlank()).orElseGet(() -> ZoneId.systemDefault().getId());
  }

  private static String trimSlash(String url) {
    return url.replaceAll("/+$", "");
  }

  /** The {@code host[:port]} half of a url — what the git credential helper matches on. */
  private static String authority(String url) {
    URI uri = URI.create(url);
    return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private long hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return ((Number) uid).longValue();
    } catch (Exception e) {
      return 1000L;
    }
  }
}
