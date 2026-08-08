package eu.wohlben.qits.projects.agenthost;

/**
 * One project agent's whole observable state: what its container is doing, whether its daemon has
 * dialled home, and which daemon build that is.
 *
 * <p>The two halves are deliberately independent. A container can be {@code RUNNING} with no daemon
 * connected — it has just started, or its daemon has dropped — and that is a real state the UI has
 * to be able to render, so it is two fields rather than one conflated status.
 *
 * <p>{@code daemonVersion} is null until a daemon says {@code Hello}, and stays null for an image
 * built without build-time filtering. That is "unknown build", never an error.
 */
public record AgentContainerState(
    AgentRuntimeStatus runtimeStatus, boolean daemonConnected, String daemonVersion) {

  /** No container, and therefore no daemon. */
  public static AgentContainerState absent() {
    return new AgentContainerState(AgentRuntimeStatus.ABSENT, false, null);
  }

  /** A container in {@code status} with nothing known about a daemon. */
  public static AgentContainerState of(AgentRuntimeStatus status) {
    return new AgentContainerState(status, false, null);
  }
}
