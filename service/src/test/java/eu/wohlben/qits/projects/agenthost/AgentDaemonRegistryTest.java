package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Heartbeat;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectChanged;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The registry's four jobs, off any container: the handshake, the build stamp it caches, the
 * activity stamp the idle sweep reads, and the nudge translation.
 *
 * <p>The connection is a reflective stub rather than a mock. Three methods of {@code
 * WebSocketConnection} are reached from here and the rest never are, so a proxy that answers those
 * three is both the whole fixture and an honest statement of what this class uses.
 */
class AgentDaemonRegistryTest {

  private static final String PROJECT = "project-1";

  private final List<ProjectChangeHint> fired = new ArrayList<>();
  private final List<String> sent = new ArrayList<>();
  private final AgentDaemonRegistry registry = new AgentDaemonRegistry();

  AgentDaemonRegistryTest() {
    DaemonMessageCodec codec = new DaemonMessageCodec();
    codec.objectMapper = new ObjectMapper();
    registry.codec = codec;
    registry.changePublisher =
        new ProjectChangePublisher() {
          @Override
          public void fire(String projectId, ProjectChangeHint.Topic topic) {
            fired.add(new ProjectChangeHint(projectId, topic));
          }
        };
    // No tunnels in this fixture: unregister asks whether one is resolvable and skips it when not,
    // which is the same path a deployment takes before the first proxied request opens one.
    @SuppressWarnings("unchecked")
    Instance<AgentTunnels> noTunnels =
        (Instance<AgentTunnels>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, args) ->
                    "isResolvable".equals(method.getName()) ? Boolean.FALSE : null);
    registry.tunnels = noTunnels;
  }

  /** A {@code WebSocketConnection} answering only what the registry actually calls on one. */
  private WebSocketConnection connection(String id) {
    return (WebSocketConnection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {WebSocketConnection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> id;
                  case "isOpen" -> Boolean.TRUE;
                  case "sendTextAndAwait" -> {
                    sent.add(String.valueOf(args[0]));
                    yield null;
                  }
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "connection " + id;
                  default -> null;
                });
  }

  @Test
  void acksAHelloAndCachesTheBuildStamp() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(
        PROJECT,
        connection,
        new Hello(PROJECT, "demo-demo", DaemonProtocol.CAPABILITY_VERSION, "2026.808.1", null));

    assertTrue(sent.get(0).contains("\"type\":\"ack\""), "the handshake is only complete on the Ack");
    AgentDaemonRegistry.DaemonInfo info = registry.lookup(PROJECT).orElseThrow();
    assertEquals("demo-demo", info.repoName());
    assertEquals("2026.808.1", info.daemonVersion());
    assertEquals(DaemonProtocol.CAPABILITY_VERSION, info.capabilityVersion());
    assertNull(info.daemonBuildTime(), "an unfiltered dev jar sends none, and that is not an error");
  }

  @Test
  void anUnparseableBuildTimeCostsTheStampAndNotTheRegistration() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new Hello(PROJECT, "demo-demo", 1, "1.0", "not a date"));

    assertNull(registry.lookup(PROJECT).orElseThrow().daemonBuildTime());
  }

  @Test
  void aHeartbeatIsLivenessAndNothingElse() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    registry.touch(PROJECT, Instant.parse("2026-08-08T00:00:00Z"));

    registry.onMessage(PROJECT, connection, new Heartbeat(PROJECT));

    assertTrue(
        registry.lastActivityAt(PROJECT).orElseThrow().isAfter(Instant.parse("2026-08-08T00:00:00Z")),
        "the stamp the idle sweep reads is the whole handling");
    assertEquals(List.of(), fired, "a heartbeat is not news for a browser");
  }

  @Test
  void translatesTheDaemonsCommandsNudgeOntoTheAgentActivityTopic() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProjectChanged(PROJECT, "COMMANDS"));

    assertEquals(
        List.of(new ProjectChangeHint(PROJECT, ProjectChangeHint.Topic.AGENT_ACTIVITY)),
        fired,
        "the daemon's commands list IS what the refinement panel renders");
  }

  @Test
  void passesThroughATopicThisBackendAlreadyHas() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProjectChanged(PROJECT, "EPICS"));

    assertEquals(List.of(new ProjectChangeHint(PROJECT, ProjectChangeHint.Topic.EPICS)), fired);
  }

  @Test
  void dropsATopicItHasNoViewFor() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProjectChanged(PROJECT, "SOMETHING_NEWER"));

    assertEquals(List.of(), fired, "a newer daemon nudging about something unknown costs nothing");
  }

  @Test
  void anAgentReportNudgesAndStamps() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(
        PROJECT,
        connection,
        new AgentActivity("cmd-1", "session-1", "BUSY", "UserPromptSubmit", null, null, 0L));

    assertEquals(
        List.of(new ProjectChangeHint(PROJECT, ProjectChangeHint.Topic.AGENT_ACTIVITY)), fired);
    assertTrue(registry.lastActivityAt(PROJECT).isPresent(), "a busy agent is not an idle project");
  }

  @Test
  void aLateCloseDoesNotEvictAReconnectedDaemon() {
    WebSocketConnection first = connection("c1");
    WebSocketConnection second = connection("c2");
    registry.register(PROJECT, first);
    registry.register(PROJECT, second);

    registry.unregister(PROJECT, first);

    assertTrue(registry.isDaemonLive(PROJECT), "the reconnect's socket is the live one");
    registry.unregister(PROJECT, second);
    assertFalse(registry.isDaemonLive(PROJECT));
  }
}
