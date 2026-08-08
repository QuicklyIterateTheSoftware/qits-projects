package eu.wohlben.qits.projects.agenthost;

/**
 * What a project's agent container is doing, as the lifecycle REST surface reports it. The wire
 * value is the constant name — the SPA switches on these five strings, so they are a published
 * contract and not an internal enum.
 */
public enum AgentRuntimeStatus {
  /** The container exists and is up. Its daemon may or may not have connected yet. */
  RUNNING,

  /** The container exists and is stopped. A {@code start} is lossless — the checkout survives. */
  STOPPED,

  /** Another request is creating or starting this project's container right now. */
  PROVISIONING,

  /** The last ensure could not produce a running container. The reason is in this service's log. */
  FAILED,

  /** No container exists for this project. */
  ABSENT
}
