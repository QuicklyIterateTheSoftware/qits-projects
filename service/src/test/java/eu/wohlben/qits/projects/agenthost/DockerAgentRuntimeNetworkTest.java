package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.agenthost.ContainerRuntime.ExecResult;
import jakarta.enterprise.inject.Vetoed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@link DockerAgentRuntime#ensureNetwork} creates when the shared network is missing.
 *
 * <p>The driver is the whole subject. A swarm service cannot join a bridge, so a bridge created
 * here would partition the platform from every agent container the moment the network had to be
 * rebuilt. An existing network is never touched, whatever its driver — this service still runs on
 * the bridge platform until the swarm re-bootstrap converts it.
 *
 * <p>Plain JUnit and no {@code @QuarkusTest}: {@link DockerAgentRuntime#runCapturing} is the single
 * place this class starts a process, so a subclass that overrides it records the argv and answers
 * with a scripted result. The suite touches no container engine.
 */
class DockerAgentRuntimeNetworkTest {

  private static final String NET = "qits-net";

  /**
   * A {@link DockerAgentRuntime} that runs nothing: every invocation is recorded and scripted.
   *
   * <p>{@code @Vetoed} is not optional. {@code @ApplicationScoped} is inherited, so without it this
   * subclass is a third {@link ContainerRuntime} bean and every test in the module fails at {@code
   * ArcProcessor#validate} with an ambiguous dependency — the failure {@link DockerAgentRuntime}'s
   * own javadoc describes. This class is built by hand here and is no one's dependency.
   */
  @Vetoed
  private static final class RecordingRuntime extends DockerAgentRuntime {

    final List<List<String>> commands = new ArrayList<>();
    /** Result by command shape, e.g. "create overlay". Absent means exit 0. */
    final Map<String, ExecResult> answers = new LinkedHashMap<>();

    @Override
    ExecResult runCapturing(List<String> command) {
      commands.add(List.copyOf(command));
      return answers.getOrDefault(shape(command), new ExecResult(0, ""));
    }

    /** What a command does, as the test talks about it — not the name it does it to. */
    static String shape(List<String> command) {
      if (command.contains("inspect")) {
        return "inspect";
      }
      return command.contains("overlay") ? "create overlay" : "create bridge";
    }

    List<String> shapes() {
      return commands.stream().map(RecordingRuntime::shape).toList();
    }
  }

  private static RecordingRuntime runtime() {
    RecordingRuntime docker = new RecordingRuntime();
    docker.runtime = "docker";
    AgentContainerFactory factory = new AgentContainerFactory();
    factory.network = NET;
    docker.containerFactory = factory;
    return docker;
  }

  @Test
  void aMissingNetworkIsCreatedAsAnAttachableOverlay() {
    RecordingRuntime docker = runtime();
    docker.answers.put("inspect", new ExecResult(1, "No such network: " + NET));

    docker.ensureNetwork();

    assertEquals(List.of("inspect", "create overlay"), docker.shapes());
    assertEquals(
        List.of("docker", "network", "create", "-d", "overlay", "--attachable", NET),
        docker.commands.get(1));
  }

  @Test
  void anExistingNetworkIsLeftAloneWhateverItsDriver() {
    RecordingRuntime docker = runtime();
    // The inspect answers 0 — a bridge on today's platform reads exactly like an overlay here, and
    // both are left as they are.

    docker.ensureNetwork();

    assertEquals(List.of("inspect"), docker.shapes());
  }

  @Test
  void aDaemonOutsideASwarmFallsBackToTheDefaultDriver() {
    RecordingRuntime docker = runtime();
    docker.answers.put("inspect", new ExecResult(1, "No such network: " + NET));
    docker.answers.put(
        "create overlay", new ExecResult(1, "Error response from daemon: This node is not a swarm"));

    docker.ensureNetwork();

    // A developer machine has no overlay driver, and an agent with no network is worse than a
    // bridge. The overlay is still tried first, so a swarm host never gets the bridge.
    assertEquals(List.of("inspect", "create overlay", "create bridge"), docker.shapes());
    assertEquals(List.of("docker", "network", "create", NET), docker.commands.get(2));
  }

  @Test
  void aNetworkCreatedByAnotherProcessIsNotCreatedAgain() {
    RecordingRuntime docker = runtime();
    docker.answers.put("inspect", new ExecResult(1, "No such network: " + NET));
    docker.answers.put(
        "create overlay",
        new ExecResult(
            1, "Error response from daemon: network with name " + NET + " already exists"));

    docker.ensureNetwork();

    // The loser of the race must not create a bridge beside the winner's overlay.
    assertEquals(List.of("inspect", "create overlay"), docker.shapes());
  }

  @Test
  void aBlankNetworkNameRunsNothing() {
    RecordingRuntime docker = runtime();
    docker.containerFactory.network = "";

    docker.ensureNetwork();

    assertTrue(docker.commands.isEmpty(), docker.commands.toString());
  }
}
