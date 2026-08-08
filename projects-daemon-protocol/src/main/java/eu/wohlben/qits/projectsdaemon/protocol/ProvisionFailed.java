package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * {@code projects-daemon} → qits: the autonomous self-clone (or submodule materialization) failed
 * in-container — the "degrade loudly" signal. The failure is recorded and reported on the
 * agent-container read, the container is left running so the error is visible.
 */
public record ProvisionFailed(String projectId, String message) implements DaemonMessage {}
