package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projectsdaemon.protocol.Ack;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.CommandChunk;
import eu.wohlben.qits.projectsdaemon.protocol.CommandExit;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.Describe;
import eu.wohlben.qits.projectsdaemon.protocol.Heartbeat;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.OpenStream;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectChanged;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectInfo;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import eu.wohlben.qits.projectsdaemon.protocol.RunCommand;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The host's live-{@code qits-projects-daemon} directory: which projects have an open control
 * socket, keyed by {@code projectId}, plus the build stamp each one announced and when it was last
 * heard from. The in-JVM half of the control plane — {@link AgentControlSocket} owns the WebSocket
 * lifecycle and forwards frames here. Adapted from qits-workspaces' {@code
 * WorkspaceDaemonRegistry}, and much smaller than it.
 *
 * <h2>What was trimmed, and why each one has no caller here</h2>
 *
 * <p>The reference correlates {@code RunCommand}/{@code Describe} round-trips, drives bootstrap
 * chains and dev-server supervision, and rolls agent activity up per workspace. None of that
 * applies: the browser drives this daemon over its own HTTP API through the reverse tunnel, so
 * nothing on the host sends a {@code RunCommand}, and a project agent runs no services and no
 * bootstrap chain. The frames those replies would arrive on are handled defensively below and
 * dropped, because a daemon must never be able to break this socket by saying something unexpected.
 *
 * <p>What is kept is exactly what the host needs: the handshake, the build stamp (so the UI can say
 * which daemon build a container is on), a last-heard-from stamp (so the idle sweep can stop a
 * container nobody is using), the change nudges, and {@link #requestStream} — the reverse tunnel's
 * one outbound message.
 */
@ApplicationScoped
public class AgentDaemonRegistry {

  private static final Logger LOG = Logger.getLogger(AgentDaemonRegistry.class);

  @Inject DaemonMessageCodec codec;

  @Inject ProjectChangePublisher changePublisher;

  /**
   * The reverse tunnel's host end. An {@code Instance<>} to break the cycle — {@link AgentTunnels}
   * asks this registry which daemons can serve a stream, and this registry tells it when one goes
   * away.
   */
  @Inject Instance<AgentTunnels> tunnels;

  private final ConcurrentHashMap<String, DaemonConnection> clients = new ConcurrentHashMap<>();

  /**
   * When each project's agent was last known to be doing something — a {@link Hello}, a {@link
   * Heartbeat}, an {@link AgentActivity} report, or a host-side start. Read by {@link
   * AgentIdleSweep} and by nothing else.
   *
   * <p>Deliberately <b>not</b> cleared on disconnect. A container whose daemon has dropped is still
   * a container the sweep has to be able to reason about, and forgetting when it was last useful
   * would make it either immortal or instantly reapable depending on which way the absence was
   * read. It is dropped only when the container is stopped.
   */
  private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();

  /**
   * Why each project's last {@link ProvisionFailed} said its {@code /workspace} is not there.
   *
   * <p>A sibling map next to {@link #lastActivity} rather than a field on the connection, and that
   * is the whole point: a daemon that cannot provision usually drops its socket soon after saying
   * so, and {@link #lookup} answers empty from that moment. A failure recorded on the connection
   * would disappear exactly when somebody came to read it.
   *
   * <p>Cleared on a {@link Provisioned}, on {@link #register} (a reconnecting daemon re-provisions
   * and its next word on the subject is the current one) and in {@link #forget} (the container is
   * going away with the volume the failure was about).
   */
  private final ConcurrentHashMap<String, String> provisionFailures = new ConcurrentHashMap<>();

  /** What a connected daemon announced about itself, for the lifecycle read and the UI. */
  public record DaemonInfo(
      Instant connectedAt,
      String repoName,
      String daemonVersion,
      Instant daemonBuildTime,
      int capabilityVersion) {}

  /** Register a freshly-connected client, replacing any stale entry for the same project. */
  public void register(String projectId, WebSocketConnection connection) {
    clients.put(projectId, new DaemonConnection(connection));
    touch(projectId);
    provisionFailures.remove(projectId);
    LOG.debugf(
        "projects-daemon connected for project %s (connection %s)", projectId, connection.id());
  }

  /**
   * Drop the client for {@code projectId}, but only if it is still the given connection — a
   * reconnect that registered a newer socket must not be evicted by the old one's late close.
   */
  public void unregister(String projectId, WebSocketConnection connection) {
    clients.computeIfPresent(
        projectId,
        (id, existing) -> existing.connection.id().equals(connection.id()) ? null : existing);
    // Pending tunnel nonces are waiting on a daemon that is no longer there; live tunnels are NOT
    // torn down, deliberately — each stream is its own TCP connection, so an open terminal survives
    // a control-socket reconnect, which is the whole reason those calls do not ride this socket.
    if (tunnels.isResolvable()) {
      tunnels.get().onDaemonGone(projectId);
    }
    LOG.debugf(
        "projects-daemon disconnected for project %s (connection %s)", projectId, connection.id());
  }

  /** Whether a project's daemon currently holds an open control socket. */
  public boolean isDaemonLive(String projectId) {
    DaemonConnection client = clients.get(projectId);
    return client != null && client.connection.isOpen();
  }

  /** What the project's connected daemon announced, or empty when none is connected. */
  public Optional<DaemonInfo> lookup(String projectId) {
    DaemonConnection client = clients.get(projectId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty();
    }
    return Optional.of(
        new DaemonInfo(
            client.connectedAt,
            client.repoName,
            client.daemonVersion,
            client.daemonBuildTime,
            client.capabilityVersion));
  }

  /** When this project's agent was last heard from, or empty when it never has been. */
  public Optional<Instant> lastActivityAt(String projectId) {
    return Optional.ofNullable(lastActivity.get(projectId));
  }

  /** Record activity now — called on every inbound frame and when the host starts a container. */
  public void touch(String projectId) {
    touch(projectId, Instant.now());
  }

  /** {@link #touch(String)} at a given instant, so a sweep test can drive a fake clock. */
  public void touch(String projectId, Instant at) {
    lastActivity.put(projectId, at);
  }

  /**
   * Record {@code at} only if this project has no stamp yet, and answer the stamp in force. The
   * sweep's entry point for a container it has found on the host but never heard from — one that
   * predates this process, or whose daemon has never connected. Without it such a container would
   * be either immortal (no stamp, never idle) or reaped on sight.
   */
  public Instant touchIfAbsent(String projectId, Instant at) {
    return lastActivity.computeIfAbsent(projectId, id -> at);
  }

  /**
   * Why this project's {@code /workspace} is not provisioned, or empty when nothing said so.
   *
   * <p>Read by {@link AgentContainers} on every lifecycle answer: a container whose self-clone
   * failed is running and useless, and reporting it {@code RUNNING} sends a browser to open a
   * terminal on an empty checkout.
   */
  public Optional<String> provisionFailure(String projectId) {
    return Optional.ofNullable(provisionFailures.get(projectId));
  }

  /** Forget a project's activity stamp and last provision failure — its container is stopped. */
  public void forget(String projectId) {
    lastActivity.remove(projectId);
    provisionFailures.remove(projectId);
  }

  /**
   * Ask a daemon to dial back and serve one stream — the reverse tunnel's only outbound message.
   *
   * <p>Sent <b>without awaiting</b>, unlike an ordinary send: this is called from a {@code
   * NetServer} connect handler, which runs on an event loop, and the blocking form would be
   * rejected by Mutiny's blocking guard there. A failure is logged and nothing else — the parked
   * socket's own TTL closes it, so a lost {@code OpenStream} degrades to a request that fails
   * rather than one that hangs.
   */
  void requestStream(String projectId, String nonce, String path) {
    DaemonConnection client = clients.get(projectId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("requestStream: no projects-daemon live for %s", projectId);
      return;
    }
    client
        .connection
        .sendText(codec.encode(new OpenStream(nonce, path)))
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf("could not ask project %s for a stream: %s", projectId, failure));
  }

  /** Handle a decoded frame from {@code qits-projects-daemon} for {@code projectId}. */
  public void onMessage(String projectId, WebSocketConnection connection, DaemonMessage message) {
    touch(projectId);
    DaemonConnection client = clients.get(projectId);
    switch (message) {
      case Hello hello -> {
        LOG.infof(
            "projects-daemon HELLO for project %s (repo %s, capability %d, daemon %s built %s)",
            hello.projectId(),
            hello.repoName(),
            hello.capabilityVersion(),
            hello.daemonVersion(),
            hello.daemonBuildTime());
        if (client != null) {
          client.repoName = hello.repoName();
          client.daemonVersion = hello.daemonVersion();
          client.daemonBuildTime = parseInstant(hello.daemonBuildTime());
          client.capabilityVersion = hello.capabilityVersion();
        }
        connection.sendTextAndAwait(codec.encode(new Ack()));
      }
      case Heartbeat ignored -> {
        /* liveness only — the touch above is the whole handling */
      }
      case DaemonLog log ->
          LOG.infof("[projects-daemon %s] %s: %s", projectId, log.level(), log.message());
      case AgentActivity activity -> onAgentActivity(projectId, activity);
      case ProjectChanged changed -> onProjectChanged(projectId, changed);
      case Provisioned provisioned -> {
        LOG.infof("projects-daemon provisioned project %s at %s", projectId, provisioned.head());
        provisionFailures.remove(projectId);
      }
      case ProvisionFailed failed -> {
        LOG.warnf(
            "projects-daemon could not provision project %s: %s", projectId, failed.message());
        // Recorded, not just logged: the container stays up and would otherwise read RUNNING while
        // its /workspace is empty. A null or blank message still counts as a failure — the state is
        // what matters and a missing reason must not read as "provisioned".
        provisionFailures.put(
            projectId,
            failed.message() == null || failed.message().isBlank()
                ? "The daemon reported a failed provision with no reason."
                : failed.message());
      }
      // Replies to frames this host never sends, and qits -> daemon requests echoed back. Both are
      // dropped rather than treated as errors: a daemon must not be able to break its own control
      // socket by saying something this backend has no view for.
      case CommandChunk ignored -> {}
      case CommandExit ignored -> {}
      case ProjectInfo ignored -> {}
      case Ack ignored -> {}
      case RunCommand ignored -> {}
      case Describe ignored -> {}
      case OpenStream ignored -> {}
    }
  }

  /**
   * A coding agent's lifecycle state changed in the container. The host caches nothing about it —
   * the browser reads the agent surface through the proxy — so the whole handling is the {@link
   * #touch} above plus a hint that says "re-read it".
   */
  private void onAgentActivity(String projectId, AgentActivity activity) {
    LOG.debugf(
        "projects-daemon agent activity for project %s: command %s is %s",
        projectId, activity.commandId(), activity.state());
    changePublisher.fire(projectId, ProjectChangeHint.Topic.AGENT_ACTIVITY);
  }

  /**
   * Relay a daemon-side change nudge onto the project's SSE stream.
   *
   * <p>The daemon owns state the host does not hold — the commands it ran, the transcripts it
   * imported — so it is the only thing that knows when a view went stale. The frame carries a topic
   * name rather than an enum precisely so a newer daemon can nudge about something this backend has
   * no view for, and that must cost nothing.
   *
   * <p><b>{@code COMMANDS} maps to {@code AGENT_ACTIVITY}</b>, and that is a translation rather than
   * a rename: the daemon's commands list <em>is</em> what the refinement panel renders, so the hint
   * that re-fetches that panel is the one the browser needs. A topic that names a {@link
   * ProjectChangeHint.Topic} outright is passed through, so a future daemon nudging {@code EPICS}
   * needs no change here. Anything else is dropped with a debug line.
   */
  private void onProjectChanged(String projectId, ProjectChanged changed) {
    ProjectChangeHint.Topic topic = topicOf(changed.topic());
    if (topic == null) {
      LOG.debugf(
          "Ignoring a projects-daemon change nudge for unknown topic '%s' (project %s)",
          changed.topic(), projectId);
      return;
    }
    changePublisher.fire(projectId, topic);
  }

  private static ProjectChangeHint.Topic topicOf(String wireTopic) {
    if (wireTopic == null) {
      return null;
    }
    if ("COMMANDS".equals(wireTopic)) {
      return ProjectChangeHint.Topic.AGENT_ACTIVITY;
    }
    try {
      return ProjectChangeHint.Topic.valueOf(wireTopic);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  /**
   * Parse the daemon's ISO-8601 build-time string, tolerating {@code null} (an older image or an
   * unfiltered dev jar) and a malformed value — either yields {@code null}, surfaced as "unknown
   * build time". A registration must never fail over a cosmetic field.
   */
  private static Instant parseInstant(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (java.time.format.DateTimeParseException e) {
      LOG.debugf("projects-daemon reported an unparseable build time '%s': %s", iso, e.getMessage());
      return null;
    }
  }

  /** One live client: its connection and what its {@link Hello} announced. */
  private static final class DaemonConnection {
    private final WebSocketConnection connection;

    /** When this control socket registered — also what a tunnel is keyed on across reconnects. */
    private final Instant connectedAt = Instant.now();

    private volatile String repoName;
    private volatile String daemonVersion;
    private volatile Instant daemonBuildTime;

    /**
     * The wire-contract version announced in {@link Hello}; 0 until one arrives. Recorded because
     * the reverse tunnel branches on it, and "has not said yet" has to read as "not capable".
     */
    private volatile int capabilityVersion;

    DaemonConnection(WebSocketConnection connection) {
      this.connection = connection;
    }
  }
}
