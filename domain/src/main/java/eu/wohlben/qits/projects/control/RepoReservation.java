package eu.wohlben.qits.projects.control;

/**
 * The outcome of holding a repository for something that is NOT a narrated process — the
 * interactive remote-login terminal. Part of the technical-process PORT (see {@link
 * TechnicalProcessRegistry}).
 */
public sealed interface RepoReservation {

  /** The repository is held; {@code token} must be handed back to release it. */
  record Acquired(String token) implements RepoReservation {}

  /** Another operation holds the repository. */
  record Conflict(String runningKind) implements RepoReservation {}
}
