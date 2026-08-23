package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceChanged;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host's live-{@code qits-workspace-daemon} directory for refinement containers, keyed by
 * refinement row id. The refinement twin of {@code agenthost/AgentDaemonRegistry}, speaking the
 * workspace protocol — {@link RefinementControlSocket} owns the WebSocket lifecycle and forwards
 * frames here.
 *
 * <h2>What is held, and for whom</h2>
 *
 * <ul>
 *   <li><b>The connection and its {@code Hello}</b> — {@code daemonConnectedAt}, {@code
 *       daemonVersion}, {@code daemonBuildTime}, {@code capabilityVersion} — for the status strip
 *       and for {@link RefinementTunnels}, which gates on the tunnel capability.
 *   <li><b>Git cleanliness</b>, daemon-reported and in-memory: the recreate gate and the status
 *       strip read it; it means nothing for a stopped container and is dropped with the connection.
 *   <li><b>Agent activity</b>, rolled up {@code BUSY > WAITING > IDLE > ENDED} across the
 *       container's live sessions, with {@code ENDED} entries aged out on read.
 *   <li><b>The provision narration</b> — {@code CommandChunk}s tagged {@code provision} routed into
 *       the ensure's {@link TechnicalProcess}, and the terminal {@code Provisioned}/{@code
 *       ProvisionFailed} settling it. The failure is also recorded beside the connection, because a
 *       daemon that cannot provision usually drops its socket right after saying so.
 * </ul>
 *
 * <p>Everything else the daemon can say — bootstrap frames (autorun is off, so only the benign
 * {@code Bootstrapped}), service transitions (autostart is off), config views, command frames with
 * other correlation ids — is dropped without ceremony: a daemon must not be able to break its own
 * control socket by saying something this host has no view for.
 */
@ApplicationScoped
public class RefinementDaemonRegistry {

  private static final Logger LOG = Logger.getLogger(RefinementDaemonRegistry.class);

  @Inject RefinementMessageCodec codec;

  @Inject RefinementChangePublisher changes;

  /** An {@code Instance<>} to break the cycle, exactly as the agent harness does. */
  @Inject Instance<RefinementTunnels> tunnels;

  /** How long an {@code ENDED} session keeps a say in the activity rollup. */
  @ConfigProperty(name = "qits.projects.refinement.ended-activity-ttl-ms", defaultValue = "1800000")
  long endedActivityTtlMs;

  private final ConcurrentHashMap<Long, DaemonConnection> clients = new ConcurrentHashMap<>();

  /** Daemon-reported working-tree cleanliness, present only while a daemon is connected. */
  private final ConcurrentHashMap<Long, Boolean> gitClean = new ConcurrentHashMap<>();

  /** Why the last provision failed — outliving the socket that reported it. */
  private final ConcurrentHashMap<Long, String> provisionFailures = new ConcurrentHashMap<>();

  /** Per-refinement, per-session agent activity, for the rollup. */
  private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ActivityEntry>> activity =
      new ConcurrentHashMap<>();

  /** The ensure's live narration, routed to from provision frames. Set by the service. */
  private final ConcurrentHashMap<Long, TechnicalProcess> provisionProcesses =
      new ConcurrentHashMap<>();

  private record ActivityEntry(String state, long atMillis) {}

  /** What a connected daemon announced about itself. */
  public record DaemonInfo(
      Instant connectedAt, String daemonVersion, Instant daemonBuildTime, int capabilityVersion) {}

  public void register(Long refinementId, WebSocketConnection connection) {
    clients.put(refinementId, new DaemonConnection(connection));
    // A reconnecting daemon re-runs its provision walk; its next word on the subject is current.
    provisionFailures.remove(refinementId);
    LOG.debugf("workspace-daemon connected for refinement %s", refinementId);
  }

  public void unregister(Long refinementId, WebSocketConnection connection) {
    clients.computeIfPresent(
        refinementId,
        (id, existing) -> existing.connection.id().equals(connection.id()) ? null : existing);
    gitClean.remove(refinementId);
    if (tunnels.isResolvable()) {
      tunnels.get().onDaemonGone(refinementId);
    }
    LOG.debugf("workspace-daemon disconnected for refinement %s", refinementId);
  }

  /** What the refinement's connected daemon announced, or empty when none is connected. */
  public Optional<DaemonInfo> lookup(Long refinementId) {
    DaemonConnection client = clients.get(refinementId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty();
    }
    return Optional.of(
        new DaemonInfo(
            client.connectedAt,
            client.daemonVersion,
            client.daemonBuildTime,
            client.capabilityVersion));
  }

  /**
   * Whether this daemon's build is older than the newest one connected to this host — {@code TRUE}
   * or {@code null}, never {@code false}, the same three-valued answer the workspaces domain gives:
   * "outdated" is a claim, "not outdated" is only ever the absence of one.
   */
  public Boolean daemonOutdated(Long refinementId) {
    DaemonConnection mine = clients.get(refinementId);
    if (mine == null || !mine.connection.isOpen()) {
      return null;
    }
    Comparator<DaemonConnection> order =
        Comparator.comparing(
                (DaemonConnection c) -> c.daemonBuildTime,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(c -> c.daemonVersion, Comparator.nullsFirst(Comparator.naturalOrder()));
    DaemonConnection latest =
        clients.values().stream()
            .filter(c -> c.connection.isOpen())
            .max(order)
            .orElse(null);
    if (latest == null || order.compare(mine, latest) >= 0) {
      return null;
    }
    return Boolean.TRUE;
  }

  /** Daemon-reported cleanliness, or empty for a daemon that has not said (or is not there). */
  public Optional<Boolean> clean(Long refinementId) {
    return Optional.ofNullable(gitClean.get(refinementId));
  }

  /** The refinement's rolled-up agent activity, or empty when no session has reported. */
  public Optional<String> agentActivity(Long refinementId) {
    Map<String, ActivityEntry> sessions = activity.get(refinementId);
    if (sessions == null || sessions.isEmpty()) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    sessions
        .entrySet()
        .removeIf(
            entry ->
                DaemonProtocol.AgentState.ENDED.equals(entry.getValue().state())
                    && now - entry.getValue().atMillis() > endedActivityTtlMs);
    return sessions.values().stream()
        .map(ActivityEntry::state)
        .max(Comparator.comparingInt(RefinementDaemonRegistry::activityRank));
  }

  private static int activityRank(String state) {
    return switch (state) {
      case DaemonProtocol.AgentState.BUSY -> 4;
      case DaemonProtocol.AgentState.WAITING -> 3;
      case DaemonProtocol.AgentState.IDLE -> 2;
      case DaemonProtocol.AgentState.ENDED -> 1;
      default -> 0;
    };
  }

  /** Why this refinement's {@code /workspace} is not provisioned, or empty. */
  public Optional<String> provisionFailure(Long refinementId) {
    return Optional.ofNullable(provisionFailures.get(refinementId));
  }

  /** Route the current ensure's narration here — provision frames append to it. */
  public void attachProvisionProcess(Long refinementId, TechnicalProcess process) {
    provisionProcesses.put(refinementId, process);
  }

  /** Forget everything about a refinement whose container is stopped or discarded. */
  public void forget(Long refinementId) {
    gitClean.remove(refinementId);
    provisionFailures.remove(refinementId);
    activity.remove(refinementId);
    provisionProcesses.remove(refinementId);
  }

  /**
   * Ask a daemon to dial back and serve one stream — the reverse tunnel's only outbound message.
   * Fire-and-forget: this runs on a {@code NetServer} connect handler's event loop.
   */
  void requestStream(Long refinementId, String nonce, String path) {
    DaemonConnection client = clients.get(refinementId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("requestStream: no workspace-daemon live for refinement %s", refinementId);
      return;
    }
    client
        .connection
        .sendText(codec.encode(new OpenStream(nonce, path)))
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf(
                    "could not ask refinement %s for a stream: %s", refinementId, failure));
  }

  /** Handle a decoded workspace-daemon frame for {@code refinementId}. */
  public void onMessage(Long refinementId, WebSocketConnection connection, DaemonMessage message) {
    DaemonConnection client = clients.get(refinementId);
    switch (message) {
      case Hello hello -> {
        LOG.infof(
            "workspace-daemon HELLO for refinement %s (workspace %s, capability %d, daemon %s)",
            refinementId, hello.workspaceId(), hello.capabilityVersion(), hello.daemonVersion());
        if (client != null) {
          client.daemonVersion = hello.daemonVersion();
          client.daemonBuildTime = parseInstant(hello.daemonBuildTime());
          client.capabilityVersion = hello.capabilityVersion();
        }
        connection.sendTextAndAwait(codec.encode(new Ack()));
      }
      case Heartbeat ignored -> {
        /* liveness only — the open socket is the signal */
      }
      case DaemonLog log ->
          LOG.infof("[workspace-daemon %s] %s: %s", refinementId, log.level(), log.message());
      case GitStatus status -> onGitStatus(refinementId, status);
      case AgentActivity report -> onAgentActivity(refinementId, report);
      case WorkspaceChanged changed -> onWorkspaceChanged(refinementId, changed);
      case CommandChunk chunk -> onCommandChunk(refinementId, chunk);
      case Provisioned provisioned -> onProvisioned(refinementId, provisioned);
      case ProvisionFailed failed -> onProvisionFailed(refinementId, failed);
      // Everything else — bootstrap frames, service transitions, config views, command exits,
      // echoes of host->daemon requests — is dropped rather than treated as an error.
      default -> LOG.tracef("dropped a %s frame for refinement %s", message.getClass(), refinementId);
    }
  }

  private void onGitStatus(Long refinementId, GitStatus status) {
    Boolean previous = gitClean.put(refinementId, status.clean());
    // Files changed whenever the tree did; the cleanliness flag itself only when it flipped. The
    // broadcaster debounces, so firing FILES on every report is a hint, not a storm.
    changes.fire(refinementId, RefinementChangeHint.Topic.FILES);
    if (previous == null || previous.booleanValue() != status.clean()) {
      changes.fire(refinementId, RefinementChangeHint.Topic.GIT_STATUS);
    }
  }

  private void onAgentActivity(Long refinementId, AgentActivity report) {
    String key =
        report.sessionId() != null && !report.sessionId().isBlank()
            ? report.sessionId()
            : report.commandId();
    if (key == null || key.isBlank() || report.state() == null) {
      return;
    }
    long at = report.at() > 0 ? report.at() : System.currentTimeMillis();
    activity
        .computeIfAbsent(refinementId, id -> new ConcurrentHashMap<>())
        .put(key, new ActivityEntry(report.state(), at));
    changes.fire(refinementId, RefinementChangeHint.Topic.AGENT_ACTIVITY);
  }

  /**
   * Relay a daemon-side change nudge onto the refinement's SSE stream. The frame carries a topic
   * name rather than an enum so a newer daemon can nudge about something this host has no view for
   * — and that must cost nothing.
   */
  private void onWorkspaceChanged(Long refinementId, WorkspaceChanged changed) {
    if (changed.topic() == null) {
      return;
    }
    try {
      changes.fire(refinementId, RefinementChangeHint.Topic.valueOf(changed.topic()));
    } catch (IllegalArgumentException unknown) {
      LOG.debugf(
          "Ignoring a workspace-daemon nudge for unknown topic '%s' (refinement %s)",
          changed.topic(), refinementId);
    }
  }

  /** Provision output, streamed into the ensure's narration. Other correlations are dropped. */
  private void onCommandChunk(Long refinementId, CommandChunk chunk) {
    if (!DaemonProtocol.PROVISION_CORRELATION_ID.equals(chunk.correlationId())) {
      return;
    }
    TechnicalProcess process = provisionProcesses.get(refinementId);
    if (process == null || process.isTerminal()) {
      return;
    }
    if (!process.isSegmentSettled(PROVISION_SEGMENT)) {
      process.openSegment(PROVISION_SEGMENT);
      process.appendLine(PROVISION_SEGMENT, chunk.text());
    }
  }

  /** The segment the daemon's self-clone output lands in. */
  static final String PROVISION_SEGMENT = "clone";

  private void onProvisioned(Long refinementId, Provisioned provisioned) {
    LOG.infof("workspace-daemon provisioned refinement %s at %s", refinementId, provisioned.head());
    provisionFailures.remove(refinementId);
    TechnicalProcess process = provisionProcesses.remove(refinementId);
    if (process != null && !process.isTerminal()) {
      if (!process.isSegmentSettled(PROVISION_SEGMENT)) {
        process.openSegment(PROVISION_SEGMENT);
        process.settleSegment(PROVISION_SEGMENT, true);
      }
      process.finishProvision(true);
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.PROCESS);
    changes.fire(refinementId, RefinementChangeHint.Topic.FILES);
  }

  private void onProvisionFailed(Long refinementId, ProvisionFailed failed) {
    String reason =
        failed.message() == null || failed.message().isBlank()
            ? "The daemon reported a failed provision with no reason."
            : failed.message();
    LOG.warnf("workspace-daemon could not provision refinement %s: %s", refinementId, reason);
    provisionFailures.put(refinementId, reason);
    TechnicalProcess process = provisionProcesses.remove(refinementId);
    if (process != null && !process.isTerminal()) {
      process.failProvision(reason);
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.PROCESS);
  }

  private static Instant parseInstant(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  /** One live client: its connection and what its {@link Hello} announced. */
  private static final class DaemonConnection {
    private final WebSocketConnection connection;
    private final Instant connectedAt = Instant.now();
    private volatile String daemonVersion;
    private volatile Instant daemonBuildTime;
    private volatile int capabilityVersion;

    DaemonConnection(WebSocketConnection connection) {
      this.connection = connection;
    }
  }
}
