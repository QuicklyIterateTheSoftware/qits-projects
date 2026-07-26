package eu.wohlben.qits.projects.control;

/** How a {@link HealthCheck} probes the service, ordered by dependency weight. */
public enum HealthCheckKind {
  /** {@code curl} against the container's loopback; the HTTP status is matched. */
  HTTP,
  /** A bare connect via bash's {@code /dev/tcp} builtin — works with zero extra tooling. */
  TCP,
  /** An arbitrary in-container script; exit 0 = healthy. The escape hatch. */
  COMMAND
}
