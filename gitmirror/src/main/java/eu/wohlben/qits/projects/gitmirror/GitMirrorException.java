package eu.wohlben.qits.projects.gitmirror;

/**
 * A git operation this module could not carry out at all — a clone or fetch that failed, a commit
 * git refused for a reason nothing here models.
 *
 * <p>Deliberately <b>not</b> raised for the answers that are answers: a conflicted merge and a
 * rejected push are both returned as records, because the caller acts on each of them differently
 * and an exception would flatten them into one 500.
 */
public class GitMirrorException extends RuntimeException {

  public GitMirrorException(String message) {
    super(message);
  }

  public GitMirrorException(String message, Throwable cause) {
    super(message, cause);
  }
}
