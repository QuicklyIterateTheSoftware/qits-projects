package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a project-agent container is actually started with.
 *
 * <p>A {@code @QuarkusTest} rather than a hand-built factory, deliberately: half of what is asserted
 * here is the <b>shipped configuration</b> — the image name, the API token, the git base, the
 * shared volume names — and a hand-built instance would prove those against values the test itself
 * chose. The argv this asserts is the argv a deployment renders.
 *
 * <p>The env block is the sharp end. Every {@code QITS_PROJECTS_DAEMON_*} name is read from the
 * daemon repo's own AGENTS.md "Environment" table, and getting one wrong fails <em>silently</em> —
 * a daemon with no url stays idle and the container simply never dials home, a daemon with no token
 * does not bind its API and every proxied request 502s. Nothing at build time on either side
 * notices, so this test is the notice.
 */
@QuarkusTest
class AgentContainerFactoryTest {

  private static final String PROJECT_ID = "11111111-2222-3333-4444-555555555555";

  @Inject AgentContainerFactory factory;

  private Map<String, String> envOf(List<String> argv) {
    Map<String, String> env = new java.util.LinkedHashMap<>();
    for (int i = 0; i < argv.size() - 1; i++) {
      if ("-e".equals(argv.get(i))) {
        String[] pair = argv.get(i + 1).split("=", 2);
        env.put(pair[0], pair.length > 1 ? pair[1] : "");
      }
    }
    return env;
  }

  private List<String> valuesAfter(List<String> argv, String flag) {
    List<String> values = new java.util.ArrayList<>();
    for (int i = 0; i < argv.size() - 1; i++) {
      if (flag.equals(argv.get(i))) {
        values.add(argv.get(i + 1));
      }
    }
    return values;
  }

  @Test
  void namesAndLabelsTheContainerAfterItsProject() {
    List<String> argv = factory.forProject(PROJECT_ID, "demo", "demo-demo").toRunArgv();

    assertEquals("qits-proj-demo", valuesAfter(argv, "--name").get(0));
    assertTrue(
        valuesAfter(argv, "--label").contains("qits.managed=project-agent"),
        "the listing and the idle sweep both filter on qits.managed");
    assertTrue(
        valuesAfter(argv, "--label").contains("qits.project=" + PROJECT_ID),
        "a deleted project's container keeps its name, so the label is what proves ownership");
    assertEquals("qits-net", valuesAfter(argv, "--network").get(0));
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"));
    assertTrue(argv.contains("--init"), "tini at PID 1, so the daemon is a reaped child");
    assertEquals("qits/project-agent:latest", argv.get(argv.size() - 1), "the image comes last");
  }

  @Test
  void publishesNoPortsAndAppendsNoCommand() {
    List<String> argv = factory.forProject(PROJECT_ID, "demo", "demo-demo").toRunArgv();

    assertFalse(argv.contains("-p"), "the daemon binds loopback; nothing is published");
    // The image is the final element, so nothing follows it: the container runs only the daemon,
    // via the image ENTRYPOINT, with no `sleep infinity` fallback to linger behind.
    assertEquals(argv.lastIndexOf("qits/project-agent:latest"), argv.size() - 1);
  }

  @Test
  void injectsTheDaemonEnvironmentContract() {
    Map<String, String> env = envOf(factory.forProject(PROJECT_ID, "demo", "demo-demo").toRunArgv());

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
        "http://qits-artifacts:8080/artifacts/git",
        env.get("QITS_PROJECTS_DAEMON_GIT_BASE"),
        "stated outright: the git host is a different service from the control socket's");
    assertEquals("qits-projects-daemon", env.get("QITS_PROJECTS_DAEMON_API_TOKEN"));
    assertEquals("13338", env.get("QITS_PROJECTS_DAEMON_API_PORT"));
    assertEquals("13337", env.get("QITS_PROJECTS_DAEMON_HOOKS_PORT"));
    assertEquals("/claude-home", env.get("QITS_PROJECTS_DAEMON_CLAUDE_MOUNT"));
  }

  @Test
  void mountsTheSharedVolumesAndTheProjectCheckout() {
    List<String> argv = factory.forProject(PROJECT_ID, "demo", "demo-demo").toRunArgv();
    List<String> mounts = valuesAfter(argv, "-v");
    Map<String, String> env = envOf(argv);

    assertTrue(
        mounts.contains("qits_shared_dot_claude:/claude-home"),
        "the same credential volume qits-workspaces mounts — shared platform-wide on purpose");
    assertTrue(mounts.contains("qits_shared_m2:/caches/m2"));
    assertTrue(mounts.contains("qits_shared_pnpm:/caches/pnpm"));
    assertTrue(
        mounts.contains("qits_project_" + PROJECT_ID + ":/workspace"),
        "the checkout volume is keyed on the project id, never the slug");
    assertEquals("/claude-home/.claude", env.get("CLAUDE_CONFIG_DIR"));
    assertEquals("-Dmaven.repo.local=/caches/m2", env.get("MAVEN_OPTS"));
  }

  @Test
  void capsMemoryHardAndLeavesTheUnsetLimitsOff() {
    List<String> argv = factory.forProject(PROJECT_ID, "demo", "demo-demo").toRunArgv();

    assertEquals(List.of("4g"), valuesAfter(argv, "--memory"));
    assertEquals(
        List.of("4g"),
        valuesAfter(argv, "--memory-swap"),
        "the same value, so the container cannot spill the difference into host swap");
    assertFalse(argv.contains("--pids-limit"), "blank config adds no flag");
    assertFalse(argv.contains("--cpus"), "blank config adds no flag");
  }
}
