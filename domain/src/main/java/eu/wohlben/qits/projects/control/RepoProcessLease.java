package eu.wohlben.qits.projects.control;

/**
 * The outcome of asking for a repository-scoped technical process. Part of the technical-process
 * PORT (see {@link TechnicalProcessRegistry}).
 *
 * <p>Kind-aware single-flight: a live operation of the SAME kind is reused (two pull walks would
 * race the bare origin's ref-locks), a live operation of a DIFFERENT kind is a conflict (a sync
 * cannot ride a pull — it would silently skip the push).
 */
public sealed interface RepoProcessLease {

  /** No operation was live: a new process was registered and is returned. */
  record Fresh(TechnicalProcess process) implements RepoProcessLease {}

  /** An operation of the same kind is already live; watch its process instead. */
  record Reused(String processId) implements RepoProcessLease {}

  /** An operation of a different kind holds the repository. */
  record Conflict(String runningKind) implements RepoProcessLease {}
}
