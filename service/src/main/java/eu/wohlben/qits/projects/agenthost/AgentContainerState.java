package eu.wohlben.qits.projects.agenthost;

/**
 * One project agent's whole observable state: what its container is doing, whether its daemon has
 * dialled home, which daemon build that is, and why it is not usable when it is not.
 *
 * <p>The two halves are deliberately independent. A container can be {@code RUNNING} with no daemon
 * connected — it has just started, or its daemon has dropped — and that is a real state the UI has
 * to be able to render, so it is two fields rather than one conflated status.
 *
 * <p>{@code daemonVersion} is null until a daemon says {@code Hello}, and stays null for an image
 * built without build-time filtering. That is "unknown build", never an error.
 */
public record AgentContainerState(
    AgentRuntimeStatus runtimeStatus,
    boolean daemonConnected,
    String daemonVersion,
    String failureDetail) {

  /** No container, and therefore no daemon. */
  public static AgentContainerState absent() {
    return new AgentContainerState(AgentRuntimeStatus.ABSENT, false, null, null);
  }

  /** A container in {@code status} with nothing known about a daemon. */
  public static AgentContainerState of(AgentRuntimeStatus status) {
    return new AgentContainerState(status, false, null, null);
  }

  /**
   * {@link AgentRuntimeStatus#FAILED} with the reason, keeping whatever is known about the daemon.
   *
   * <p>A detail rather than a sixth status constant: the SPA switches on the five status strings
   * and they are a published contract, so a new one would break a client that has every case
   * covered. The status says the agent is unusable; this says why.
   *
   * <p><b>There is no re-provision.</b> A daemon latches its provision attempt for the life of its
   * process and {@code ensure} no-ops on a container that is already running, so nothing retries
   * the self-clone in place. Recovery is to remove the container and ensure it again, which starts
   * a new daemon process — and that is deliberately not automatic here, because the {@code
   * /workspace} volume a remove would orphan is where uncommitted work lives.
   */
  public AgentContainerState failedWith(String detail) {
    return new AgentContainerState(
        AgentRuntimeStatus.FAILED, daemonConnected, daemonVersion, detail);
  }
}
