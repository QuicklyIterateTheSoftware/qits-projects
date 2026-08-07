package eu.wohlben.qits.projects.entity;

/**
 * How the last backup of a repository onto its forge twin went.
 *
 * <p>Four values rather than a boolean, because the three ways it can fail want three different
 * things from a person and a screen that cannot tell them apart teaches nobody anything: {@link
 * #AUTH_REQUIRED} is a sign-in away, {@link #UNREACHABLE} is usually somebody else's outage and
 * fixes itself, and {@link #FAILED} is the one worth reading the detail line for.
 */
public enum BackupOutcome {
  /** Every branch and tag the git host holds reached the twin. */
  SUCCEEDED,
  /**
   * The forge refused for want of credentials. The remedy is the sign-in terminal, and it fixes
   * every repository on that host at once — the credential store is shared.
   */
  AUTH_REQUIRED,
  /** The forge could not be reached at all: dns, a refused connection, a timeout. */
  UNREACHABLE,
  /** Anything else — a rejected ref update, a repository the forge does not have. */
  FAILED
}
