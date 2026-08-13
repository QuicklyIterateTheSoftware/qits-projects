package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.PolicyType;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.SharedMount;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeMount;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * What a project-agent container is actually started with — the request qits-containers is handed.
 *
 * <p>A {@code @QuarkusTest} rather than a hand-built factory, deliberately: half of what is asserted
 * here is the <b>shipped configuration</b> — the image name, the API token, the git base, the shared
 * volume names — and a hand-built instance would prove those against values the test itself chose.
 * The spec this asserts is the spec a deployment sends.
 *
 * <p>The env block is the sharp end. Every {@code QITS_PROJECTS_DAEMON_*} name is read from the
 * daemon repo's own AGENTS.md "Environment" table, and getting one wrong fails <em>silently</em> — a
 * daemon with no url stays idle and the container simply never dials home, a daemon with no token
 * does not bind its API and every proxied request 502s. Nothing at build time on either side
 * notices, so this test is the notice.
 *
 * <p>The sandbox is the other. Two flags are deliberately <b>off</b> here that are on for a CI step
 * container, and a refactor that turned them on would break the toolchain this image exists to
 * carry — so their absence is asserted as hard as the limits' presence.
 */
@QuarkusTest
class AgentContainerFactoryTest {

  private static final String PROJECT_ID = "11111111-2222-3333-4444-555555555555";

  /**
   * Read from config rather than written down, unlike every other shipped value this class asserts.
   * The reference is a version pin that a release train rewrites on every qits-projects-daemon
   * release, so a literal here would go red on a bump that is working exactly as intended. What is
   * still under test is that the shipped value is a qualified, pinned reference at all.
   */
  private static final String IMAGE =
      ConfigProvider.getConfig().getValue("qits.projects.agent-image", String.class);

  @Inject AgentContainerFactory factory;

  private Spec spec() {
    return factory.forProject(PROJECT_ID, "demo", "demo-demo").spec();
  }

  @Test
  void namesAndLabelsTheContainerAfterItsProject() {
    Spec spec = spec();

    assertEquals("qits-proj-demo", spec.explicitName(), "the orchestrator would derive one");
    assertEquals(
        Map.of("qits.managed", "project-agent", "qits.project", PROJECT_ID),
        spec.extraLabels(),
        "human hints: the orchestrator's own qits.containers.* labels name the place");
    assertEquals("qits-net", spec.network());
    assertEquals(List.of("host.docker.internal:host-gateway"), spec.addHosts());
    assertEquals(Boolean.TRUE, spec.init(), "tini at PID 1, so the daemon is a reaped child");
    assertEquals(IMAGE, spec.image());
  }

  /**
   * The reference must name a registry, because it is resolved by the daemon the orchestrator holds:
   * a bare name resolves against whatever is lying in that host's local image store, which is how
   * {@code qits/project-agent:native} came to be a hand-built tag nobody could rebuild. It must also
   * carry a tag that is not {@code latest}, so one qits-projects release always starts one agent
   * image.
   */
  @Test
  void shipsAQualifiedVersionPinnedReference() {
    String repository = IMAGE.substring(0, IMAGE.lastIndexOf(':'));

    assertTrue(
        repository.contains("/") && repository.substring(0, repository.indexOf('/')).contains(":"),
        "the registry host is part of the value: " + IMAGE);
    assertFalse(IMAGE.endsWith(":latest"), "a floating tag makes the running daemon unanswerable");
  }

  /**
   * The container runs only {@code qits-projects-daemon}, via the image ENTRYPOINT, with no
   * {@code sleep infinity} fallback: one that cannot run the daemon must fail to start rather than
   * linger with this service's uid and mounts and no control plane reaching it.
   */
  @Test
  void overridesNoEntrypointAndAppendsNoCommand() {
    Spec spec = spec();

    assertNull(spec.entrypoint());
    assertNull(spec.args());
  }

  @Test
  void injectsTheDaemonEnvironmentContract() {
    Map<String, String> env = spec().env();

    assertEquals(
        "ws://qits-projects:8080/projects/daemon/" + PROJECT_ID,
        env.get("QITS_PROJECTS_DAEMON_URL"),
        "an append-only cross-repo path; the daemon dials it verbatim and parses nothing out of it");
    assertEquals(
        "/projects/container/" + PROJECT_ID + "/",
        env.get("QITS_PROJECTS_DAEMON_API_BASE_PATH"),
        "the daemon is TOLD its own address because no hop in the chain rewrites a path");
    assertEquals(PROJECT_ID, env.get("QITS_PROJECTS_DAEMON_PROJECT_ID"));
    assertEquals(
        "demo-demo",
        env.get("QITS_PROJECTS_DAEMON_REPO_NAME"),
        "the wrapper is <slug>-<slug>, and the clone is always name-addressed");
    assertEquals(
        "http://dev-qits-githost:8080/git",
        env.get("QITS_PROJECTS_DAEMON_GIT_BASE"),
        "stated outright: the git host is a different service from the control socket's");
    assertEquals("qits-projects-daemon", env.get("QITS_PROJECTS_DAEMON_API_TOKEN"));
    assertEquals("13338", env.get("QITS_PROJECTS_DAEMON_API_PORT"));
    assertEquals("13337", env.get("QITS_PROJECTS_DAEMON_HOOKS_PORT"));
    assertEquals("/claude-home", env.get("QITS_PROJECTS_DAEMON_CLAUDE_MOUNT"));
  }

  @Test
  void statesTheOneMcpServerAndNoOther() {
    Map<String, String> env = spec().env();

    // Same host and port as the control socket above, and this service's own MCP root path — the
    // server carrying the epic tools a refinement session drafts through. Stated, so the daemon does
    // not have to derive it from the socket's authority.
    assertEquals("http://qits-projects:8080/projects/mcp", env.get("QITS_REPOSITORY_MCP_URL"));
    assertEquals(
        ConfigProvider.getConfig()
            .getValue("quarkus.mcp.server.repository.http.root-path", String.class),
        java.net.URI.create(env.get("QITS_REPOSITORY_MCP_URL")).getPath(),
        "the url is composed from a literal; this is what notices when the mount moves");
    // Exactly one. qits-workspace-daemon wires actions/repository/observability; a refinement agent
    // gets the plan and nothing else, so no second MCP address is injected here on purpose.
    assertEquals(
        List.of("QITS_REPOSITORY_MCP_URL"),
        env.keySet().stream().filter(name -> name.contains("MCP")).toList());
  }

  /**
   * The checkout is the workload's <b>own</b> volume and the other three are the platform's, and the
   * wire distinguishes them for a reason this harness leans on: a shared mount is never claimed,
   * created or removed on this workload's behalf, and an owned one under {@code IDLE_STOP} is not
   * removed either. Nothing here discards a checkout, and nothing here can take the platform's
   * credential volume away from qits-workspaces.
   */
  @Test
  void mountsTheSharedVolumesAndTheProjectCheckout() {
    Spec spec = spec();
    Map<String, String> env = spec.env();

    assertEquals(
        List.of(new VolumeMount("qits_project_" + PROJECT_ID, "/workspace")),
        spec.volumeMounts(),
        "the checkout volume is keyed on the project id, never the slug");
    assertEquals(
        List.of(
            new SharedMount("qits_shared_dot_claude", "/claude-home"),
            new SharedMount("qits_shared_m2", "/caches/m2"),
            new SharedMount("qits_shared_pnpm", "/caches/pnpm")),
        spec.sharedMounts(),
        "the same names qits-workspaces uses — shared platform-wide on purpose");
    assertEquals("/claude-home/.claude", env.get("CLAUDE_CONFIG_DIR"));
    assertEquals("-Dmaven.repo.local=/caches/m2", env.get("MAVEN_OPTS"));
  }

  @Test
  void capsMemoryHardAndLeavesTheUnsetLimitsOff() {
    Spec spec = spec();

    assertEquals("4g", spec.security().memory());
    assertEquals(
        "4g",
        spec.security().memorySwap(),
        "the same value, so the container cannot spill the difference into host swap");
    assertNull(spec.security().pidsLimit(), "blank config sets no cap");
    assertNull(spec.security().cpus(), "blank config sets no cap");
  }

  /**
   * The two flags a CI step container carries and this one must not. A step runs a repository's own
   * script and is fenced; a project agent is a development environment a person works in, running an
   * image the platform built, and dropping every capability there would break the toolchain it
   * exists to carry. Turning either on is a change to what this container is for, not a hardening
   * tweak.
   *
   * <p>The docker socket is the third, and it is absent for the plainer reason: a project agent
   * builds inside itself and publishes nothing.
   */
  @Test
  void isNotSandboxedLikeACiStepAndHoldsNoDockerSocket() {
    Spec spec = spec();

    assertFalse(spec.security().capDropAll());
    assertFalse(spec.security().noNewPrivileges());
    assertFalse(spec.hostDockerSocket());
  }

  /**
   * The lifecycle, and the two ways an ensure may be answered.
   *
   * <p>{@code IDLE_STOP} is what makes the orchestrator's own sweep a belt under this service's, and
   * what makes it refuse to remove the checkout volume. {@code Recreate.never} is what keeps a spec
   * change from replacing a container somebody is working in.
   */
  @Test
  void asksForAStoppableLifetimeAndNeverAnUnaskedReplacement() {
    EnsureRequest request = factory.forProject(PROJECT_ID, "demo", "demo-demo");

    assertEquals(PolicyType.IDLE_STOP, request.policy().type());
    assertEquals(
        ConfigProvider.getConfig()
            .getValue("qits.projects.agent-idle-timeout", java.time.Duration.class)
            .toSeconds(),
        request.policy().idleAfterSeconds(),
        "one window, two sweeps: this service's and the orchestrator's belt under it");
    assertEquals(Recreate.never, request.recreate());
  }

  /**
   * Bringing a stopped agent back is the one ask that may replace a container, and it takes both
   * halves to work: the permission, and a spec that differs from the stored one. qits-containers has
   * no start verb, and its unchanged-spec path cannot start a container whose name docker already
   * holds — see {@code ContainerRuntime.restart}.
   */
  @Test
  void aRecreationAsksForAReplacementAndCarriesSomethingToReplaceOn() {
    EnsureRequest first = factory.forRecreation(PROJECT_ID, "demo", "demo-demo");
    EnsureRequest second = factory.forRecreation(PROJECT_ID, "demo", "demo-demo");

    assertEquals(Recreate.ifChanged, first.recreate());
    assertNotEquals(
        first.spec().env().get(AgentContainerFactory.INCARNATION),
        second.spec().env().get(AgentContainerFactory.INCARNATION),
        "a stamp that repeated would be a spec that did not change");
    assertNull(
        spec().env().get(AgentContainerFactory.INCARNATION),
        "a first provision carries none: there is nothing there to replace");
  }
}
