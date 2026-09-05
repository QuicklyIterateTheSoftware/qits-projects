package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.SharedMount;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeMount;
import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The whole of what a project-agent container is started with, as the orchestrator's wire spells it.
 * Routing all creation through {@link #forProject} makes it structurally impossible to start an
 * agent without the shared credential volume, the checkout volume, the docker-host alias, the host
 * uid, the daemon's dial-home identity and — where a deployment has an idp — the container's own
 * commissioned platform credential.
 *
 * <p><b>The two arms differ about that credential and about nothing else new.</b>
 * {@link #forProject} commissions one, {@link #forRestart} reads back the one the container already
 * holds; {@link AgentCommissions} carries why those are two different questions and why a wake that
 * asked for a fresh pair would stop being a start in place.
 *
 * <p><b>It builds an {@link EnsureRequest} and no argv.</b> The {@code docker run} argv builder this
 * class used to render into was deleted with the docker runtime: this service spawns no process, so
 * the cheapest process library is no process library. Every field below lands in the same place it
 * landed as a flag, on the far side of one HTTP call.
 *
 * <p><b>The shared volumes are shared platform-wide on purpose</b>, and they are {@link SharedMount}s
 * rather than {@link VolumeMount}s, which is the wire saying exactly that: a shared mount is the
 * platform's and is never claimed, created or removed on this workload's behalf. The credential
 * volume and the two build caches carry the same names qits-workspaces uses, so a one-time
 * {@code claude} login and a dependency downloaded by any container are seen by every other one.
 * Diverging the names here would give a project agent its own unauthenticated agent home.
 *
 * <p><b>The checkout is the one volume this workload owns.</b> {@code qits_project_<projectId>} is a
 * {@link VolumeMount}, so the orchestrator claims a row for it and creates it before the container
 * mounts it — and under {@code IDLE_STOP} it will not remove it even when asked, which is the
 * wire-level spelling of "nothing here discards a checkout".
 */
@ApplicationScoped
public class AgentContainerFactory {

  private static final Logger LOG = Logger.getLogger(AgentContainerFactory.class);

  /**
   * The registry host and path of the image the per-project agent runs — the workspace toolchain
   * plus the daemon binary ({@code registry.dev.localhost:8080/qits/project-agent}). It is the fixed
   * half of the reference: {@link #imageVersion} carries the calver tag, and {@link #image()} joins
   * them as {@code <repo>:<version>}.
   *
   * <p><b>The registry host is part of the value.</b> A bare name would resolve against whatever is
   * lying in the host daemon's local image store — the {@code qits/project-agent:native} drift this
   * shape exists to end — so the value handed to {@code docker run} must be fully qualified. The
   * reasoning for both halves lives in {@code application.properties}.
   */
  @ConfigProperty(name = "qits.projects.agent-image-repo")
  String imageRepo;

  /**
   * The released calver the agent image is pinned to. Read from config, never a constant, because
   * the deployer injects {@code QITS_PROJECTS_AGENT_IMAGE_VERSION} — sourced from qits-configuration,
   * kept in step by the project-agent's own {@code SoftwareRelease} event — and SmallRye maps that
   * env var onto this property automatically, so the injected value wins over the {@code
   * application.properties} default.
   */
  @ConfigProperty(name = "qits.projects.agent-image-version")
  String imageVersion;

  /**
   * The fully qualified, version-pinned image reference: {@code <repo>:<version>}. Composed rather
   * than stored so the version half can be overridden at runtime by an env var while the registry
   * host and path stay committed. The result is byte-identical in shape to the old single key —
   * {@code registry.dev.localhost:8080/qits/project-agent:<calver>}.
   */
  String image() {
    return imageRepo + ":" + imageVersion;
  }

  /**
   * The two halves of that reference, readable apart from the reference {@link #image()} joins them
   * into. They exist for the launch-pin route ({@code GET /projects/api/pins}), which has to name the
   * repository and the tag separately and must not re-declare either config key: the image a pin
   * names and the image a launch pulls are the same value or the pin is worthless. Splitting {@link
   * #image()} back on a colon would not do — the repo half carries the registry's own {@code
   * host:port}.
   */
  public String imageRepo() {
    return imageRepo;
  }

  /** The agent image's calver tag; see {@link #imageRepo()}. */
  public String imageVersion() {
    return imageVersion;
  }

  /**
   * The shared Docker network every agent container joins (and qits-projects is on), so the daemon
   * can dial this service's control socket by DNS name with no host-port publishing. It is the
   * bootstrap's to create and the orchestrator's to join; nothing here makes one.
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
   * Hard memory cap ({@code memory} plus {@code memorySwap} at the same value). Without it the
   * container sees the whole host's RAM and every JVM inside sizes its default heap against that —
   * a build run during refinement can then OOM the host. Blank disables the cap; the shipped
   * default matches qits-workspaces' {@code 4g}.
   */
  @ConfigProperty(name = "qits.projects.agent-memory-limit")
  Optional<String> memoryLimit;

  /** Process/thread cap (fork-bomb guard). Blank/absent disables. */
  @ConfigProperty(name = "qits.projects.agent-pids-limit")
  Optional<String> pidsLimit;

  /** CPU cap. Blank/absent disables. */
  @ConfigProperty(name = "qits.projects.agent-cpus")
  Optional<String> cpus;

  /**
   * OOM-killer bias for an agent container. MEDIUM (800) reaps agents under host memory pressure
   * after ci build containers (1000) but before workspaces (600).
   */
  @ConfigProperty(name = "qits.projects.agent-oom-score-adj", defaultValue = "800")
  Integer agentOomScoreAdj;

  /**
   * The window the <b>orchestrator's</b> own idle sweep measures, the same key {@link AgentIdleSweep}
   * reads for this service's. One key, two sweeps, and that is deliberate: the host's is what tears
   * the tunnel down and forgets the registry entry, which the orchestrator cannot do, and the
   * orchestrator's is the belt that still stops a container if this process dies holding one.
   *
   * <p>A zero or negative value disables the host sweep; here it means the workload gets an
   * {@code EXPLICIT} lifetime instead, since {@code idleStop(0)} would ask the orchestrator to stop
   * every agent on its next pass.
   */
  @ConfigProperty(name = "qits.projects.agent-idle-timeout", defaultValue = "PT4H")
  Duration idleTimeout;

  /**
   * The address a container reaches this service at — the authority of {@code
   * QITS_PROJECTS_DAEMON_URL}. Composed here because the daemon runs in-container and cannot
   * resolve it: it dials the url it was handed, verbatim, and parses no path out of it.
   */
  @ConfigProperty(name = "qits.projects.own-host", defaultValue = "qits-projects")
  String ownHost;

  @ConfigProperty(name = "qits.projects.own-port", defaultValue = "8080")
  String ownPort;

  /** IdP base used by this service and handed to commissioned daemons for token exchange. */
  @ConfigProperty(name = "quarkus.oidc-client.auth-server-url")
  String idpAuthServerUrl;

  /** This service is also the audience protecting its daemon control socket. */
  @ConfigProperty(name = "quarkus.oidc-client.client-id")
  String platformClientId;

  /** Audience the commissioned container requests for its direct qits-githost reads. */
  @ConfigProperty(
      name = "quarkus.oidc-client.githost.grant-options.client.audience",
      defaultValue = "qits-githost")
  String gitHostAudience;

  /**
   * The git host the daemon's boot self-clone reads from, including qits-githost's own {@code /git}
   * prefix. Stated outright rather than left to the daemon's derivation, which would guess a
   * <em>different</em> service's address off this one's authority and say so in a WARN.
   */
  @ConfigProperty(
      name = "qits.projects.agent-git-base",
      defaultValue = "http://dev-qits-githost:8080/git")
  String gitBase;

  /**
   * The <b>one</b> MCP server an agent launch attaches — this service's own, at {@code
   * /projects/mcp}, carrying the epic tools a refinement session drafts through. Absent, it is
   * composed from {@link #ownHost}/{@link #ownPort}, which is the same address the control socket
   * already names, so a deployment needs no configuration for it.
   *
   * <p>Stated rather than left to the daemon's own derivation. The daemon can derive it — the
   * control socket and this server are the same service — but that soundness is a property of
   * today's topology, and the day the MCP server is deployed apart the derivation becomes a guess
   * with nothing saying so. This key is where that move is made.
   */
  @ConfigProperty(name = "qits.projects.agent-mcp-url")
  Optional<String> agentMcpUrl;

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

  /**
   * Where the container's own platform credential comes from — commissioned for a fresh container,
   * read back for one being woken. See {@link AgentCommissions}, which carries why those are two
   * different questions.
   */
  @Inject AgentCommissions commissions;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /**
   * The {@code qits.managed} value every container here carries.
   *
   * <p><b>It selects nothing any more.</b> Both readers of it are gone: the listing asks the
   * orchestrator for this owner's own rows, and the idle sweep iterates that listing. It stays as an
   * extra label because it is what a person reading {@code docker ps} has to go on, beside the
   * orchestrator's own {@code qits.containers.*} labels which name the place rather than the
   * project.
   */
  public static final String MANAGED_LABEL_VALUE = "project-agent";

  /** The {@code qits.managed} value the per-project checkout volume carries. */
  public static final String MANAGED_VOLUME_LABEL_VALUE = "project-volume";

  /**
   * The deterministic container name for a project: {@code qits-proj-<slug>}.
   *
   * <p>The slug, not the id, so a human reading {@code docker ps} can tell whose agent is running —
   * and it travels as the spec's {@code explicitName}, because the orchestrator would otherwise
   * derive a name of its own. It is a <b>hint and not the address</b>: the place is
   * {@code owner/project-agent/<projectId>}, so a slug this name is derived from proves nothing
   * about ownership. What it can still do is collide, and {@code ContainersAgentRuntime.run} is
   * where that is answered.
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
   * qits.managed} value (so a person can tell a checkout from a container) plus the project id, so a
   * dangling volume is readable and matchable to its row.
   */
  public Map<String, String> projectVolumeLabels(String projectId) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.managed", MANAGED_VOLUME_LABEL_VALUE);
    labels.put("qits.project", projectId);
    return labels;
  }

  /**
   * The whole ensure request for one project: its deterministic name, the host uid, the two {@code
   * qits.*} hint labels, the {@code host.docker.internal} alias Linux needs, the shared network, the
   * git commit identity, the shared credential and build-cache volumes, the per-project checkout
   * volume, the configured resource limits, the image, and the whole of {@code
   * qits-projects-daemon}'s dial-home environment.
   *
   * <p>Every {@code QITS_PROJECTS_DAEMON_*} name below is read from the daemon repo's own {@code
   * AGENTS.md} "Environment" table, not invented here. Two of them are append-only cross-repo path
   * contracts and are composed from {@link DaemonProtocol}'s constants so the literals live in one
   * place: the control socket and the proxy base path.
   *
   * <p><b>The sandbox flags are deliberately off, and that is the one place this spec differs in
   * kind from qits-ci's.</b> A CI step runs a repository's own script and is fenced with
   * {@code capDropAll} and {@code noNewPrivileges}; a project agent is a development environment a
   * person works in, running an image the platform built, and dropping every capability there would
   * break the toolchain it exists to carry. What is on is the resource envelope — memory, swap,
   * pids, cpus — which is what stops one build OOMing the host.
   *
   * <p><b>{@code init} is on</b>, which is the one flag the old argv set unconditionally: tini at
   * PID 1 means the daemon is a reaped child and a long-lived container that spawns agents,
   * compilers and test runners does not collect zombies for as long as it runs.
   *
   * <p><b>{@code Recreate.never}</b>, so a spec change never replaces a container somebody is
   * working in. An agent image bump is picked up the next time the agent is woken from a stop —
   * see {@link #forRestart}, which is the one ask that permits a replacement.
   *
   * <p><b>This is the arm that commissions</b> — a fresh container is a fresh context and gets a
   * credential of its own, handed back when the container ends. It is also the arm that can
   * <em>fail</em>: an idp that cannot answer inside the window throws, and the ladder reports
   * {@code FAILED} rather than starting a container whose every read will be refused later.
   */
  public EnsureRequest forProject(String projectId, String projectSlug, String repoName) {
    return request(
        projectId, projectSlug, repoName, Recreate.never, commissions.forFreshContainer(projectId));
  }

  /**
   * The same spec, asking for a replacement <b>only if it has actually changed</b> — what a stopped
   * agent is woken with. {@code ContainerRuntime.restart} carries the argument in full.
   *
   * <p><b>One difference from {@link #forProject}, and it is the whole of it:</b>
   * {@code Recreate.ifChanged}. Under an <em>unchanged</em> spec the orchestrator starts the
   * container this place already has, in place, keeping its docker id and everything outside its
   * volumes; under a changed one — an agent-image bump landing while the agent was asleep — it
   * replaces it, which is the only moment a bump can be picked up without taking a container away
   * from somebody working in it.
   *
   * <p>So waking is a <b>start</b> in the ordinary case and a replacement only when there is
   * something to replace on. It did not used to be: qits-containers had no start verb, an ensure of
   * a stopped place under an unchanged spec could not bring it back, and this method forced the
   * replacement by stamping a per-call value into the environment. That is gone with the defect —
   * qits-containers 354fd7f — and nothing is left in its place, because a request that differs on
   * every call is a request that can never be started in place.
   *
   * <p><b>It commissions nothing.</b> The container being woken already holds the credential it was
   * created with, so this arm reads that same pair back out of {@link AgentCommissions} and sends it
   * unchanged. A fresh one would differ from the stored spec on every call and would take the
   * start-in-place away again, which is the whole of the paragraph above.
   */
  public EnsureRequest forRestart(String projectId, String projectSlug, String repoName) {
    return request(
        projectId,
        projectSlug,
        repoName,
        Recreate.ifChanged,
        commissions.forExistingContainer(projectId));
  }

  private EnsureRequest request(
      String projectId,
      String projectSlug,
      String repoName,
      Recreate recreate,
      Optional<AgentCredentials.Commissioned> credential) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("TZ", timezone());
    // The dial-home url, composed here because the daemon runs in-container and cannot resolve this
    // service's address. It dials this verbatim and parses no path out of it, which is why the path
    // prefix comes from the protocol constant rather than a literal typed twice.
    env.put(
        "QITS_PROJECTS_DAEMON_URL",
        "ws://" + ownHost + ":" + ownPort + DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + projectId);
    // The path ContainerProxyRoute addresses this container at. The proxy forwards a caller's path
    // untouched, so the daemon has to be TOLD which leading part of it is its own address rather
    // than deriving one by stripping a segment. Injected from ContainerProxyPath so the route and
    // the container's idea of the route cannot drift.
    env.put("QITS_PROJECTS_DAEMON_API_BASE_PATH", ContainerProxyPath.base(projectId));
    env.put("QITS_PROJECTS_DAEMON_PROJECT_ID", projectId);
    // The wrapper repository the daemon self-clones. The clone is always name-addressed
    // (<gitBase>/<projectId>/<repoName>) because a wrapper's submodule urls are relative and an
    // id-addressed root breaks every one of them, so both halves are required.
    env.put("QITS_PROJECTS_DAEMON_REPO_NAME", repoName);
    // Stated, never derived: the git host is qits-githost, a different service from the one the
    // control socket points at, so the daemon's own fallback would be a guess with a WARN on it.
    env.put("QITS_PROJECTS_DAEMON_GIT_BASE", gitBase);
    // The bearer the daemon's loopback API requires. Unset means the API does not bind at all.
    env.put("QITS_PROJECTS_DAEMON_API_TOKEN", daemonApiToken);
    // The container's OWN platform credential, commissioned from qits-idp for this container and
    // handed back when it ends — what its pulls, its maven and npm resolution and its git reads
    // authenticate with once anonymous reads are refused at the edge. It is a different KIND of
    // value from the token above: that one is peer authentication behind a loopback bind and is
    // shared by every agent, this one names one container and nothing else.
    //
    // Absent whenever this deployment commissions nothing (no idp), and then these two names are
    // simply not in the map — the spec is byte for byte the spec it was before commissioning
    // existed, which is what keeps "no idp" a supported configuration rather than a degraded one.
    credential.ifPresent(
        pair -> {
          env.put("QITS_COMMISSIONED_CLIENT_ID", pair.clientId());
          env.put("QITS_COMMISSIONED_CLIENT_SECRET", pair.secret());
          env.put(
              "QITS_PROJECTS_DAEMON_AUTH_TOKEN_URL",
              idpAuthServerUrl.replaceAll("/+$", "") + "/token");
          env.put("QITS_PROJECTS_DAEMON_AUTH_AUDIENCE", platformClientId);
          env.put("QITS_PROJECTS_DAEMON_GIT_AUTH_AUDIENCE", gitHostAudience);
        });
    env.put("QITS_PROJECTS_DAEMON_API_PORT", Integer.toString(daemonApiPort));
    env.put("QITS_PROJECTS_DAEMON_HOOKS_PORT", Integer.toString(daemonHooksPort));
    env.put("QITS_PROJECTS_DAEMON_CLAUDE_MOUNT", claudeMount);
    // The one MCP server a launch in this container attaches: this service, at /projects/mcp, where
    // the epic tools live. Stated rather than derived — see the field's javadoc.
    //
    // Exactly one, deliberately. qits-workspace-daemon wires three (actions, repository,
    // observability); none of the other two is named here or addressable there, because a
    // refinement agent's job is the project's PLAN, not workspace actions or another service's
    // telemetry. The name has no QITS_PROJECTS_DAEMON_ prefix because it is the daemon's existing
    // `qits.repository-mcp.url` key.
    env.put("QITS_REPOSITORY_MCP_URL", agentMcpUrl());
    // The commit identity as container-level env, so every git process in the container inherits it
    // regardless of cwd or .git/config — identity env beats every git config level.
    gitIdentity.envMap().forEach(env::put);

    List<SharedMount> shared = new ArrayList<>();
    if (claudeVolume != null && !claudeVolume.isBlank()) {
      shared.add(new SharedMount(claudeVolume, claudeMount));
      // Point every in-container `claude` at the shared credential dir regardless of HOME: the
      // image sets HOME to the checkout, so without this a login would land there — container-local
      // and invisible to every other container.
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

    // Insertion-ordered, so the request body a test asserts is the same body every time. The
    // orchestrator sorts them again on its own side before they reach an argv.
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.managed", MANAGED_LABEL_VALUE);
    labels.put("qits.project", projectId);

    String memory = memoryLimit.filter(value -> !value.isBlank()).orElse(null);
    Spec spec =
        new Spec(
            image(),
            // No entrypoint and no command: the container runs only qits-projects-daemon, via the
            // image ENTRYPOINT, and deliberately has no `sleep infinity` fallback — a container that
            // cannot run the daemon must fail to start rather than linger with this service's uid
            // and mounts and no control plane reaching it.
            null,
            null,
            env,
            // Human hints. They select nothing; see MANAGED_LABEL_VALUE.
            labels,
            network,
            null,
            // Linux needs this for host.docker.internal to resolve to the docker bridge gateway.
            List.of("host.docker.internal:host-gateway"),
            // The per-project checkout, on a named volume rather than the container's writable
            // layer, so a recreation reattaches it and the daemon skips its self-clone.
            List.of(new VolumeMount(projectVolumeName(projectId), "/workspace")),
            shared,
            // No docker socket. A project agent builds inside itself; it does not publish images.
            false,
            new Security(
                false,
                false,
                memory,
                memory,
                pids(),
                cpus.filter(v -> !v.isBlank()).orElse(null),
                agentOomScoreAdj),
            null,
            containerName(projectSlug),
            Long.toString(hostUid()),
            // tini at PID 1 — see the method javadoc. The old argv set --init unconditionally and
            // this is that flag, unchanged.
            true);
    return new EnsureRequest(spec, policy(), recreate);
  }

  /**
   * The lifecycle the orchestrator holds this workload to. {@code IDLE_STOP} is the one that says
   * "stoppable, and its volume is what it comes back to" — under it the orchestrator refuses to
   * remove the checkout even when a delete asks for volumes, which is the wire-level guarantee
   * behind this harness's whole stop policy.
   */
  private Policy policy() {
    if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
      return Policy.explicitLifetime();
    }
    return Policy.idleStop(idleTimeout.toSeconds());
  }

  /** The pids cap as the wire wants it, or none. A value that is not a number is not a cap. */
  private Long pids() {
    String value = pidsLimit.filter(text -> !text.isBlank()).orElse(null);
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException e) {
      LOG.warnf("qits.projects.agent-pids-limit is not a number ('%s'); no pids cap is set", value);
      return null;
    }
  }

  /**
   * The configured MCP url, or this service's own {@code /projects/mcp} composed from the same
   * host/port the control socket uses. Never blank: an empty env would leave the daemon deriving
   * one, which is the state this key exists to leave behind.
   */
  private String agentMcpUrl() {
    return agentMcpUrl
        .filter(url -> !url.isBlank())
        .orElseGet(() -> "http://" + ownHost + ":" + ownPort + "/projects/mcp");
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
