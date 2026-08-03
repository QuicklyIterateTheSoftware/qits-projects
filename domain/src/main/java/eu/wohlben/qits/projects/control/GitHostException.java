package eu.wohlben.qits.projects.control;

/**
 * {@link GitHostRepositories} could not answer — the host rejected the request, answered with a
 * status neither {@code ensure} nor {@code find} expects, or was unreachable. Unchecked, because
 * every caller of a mandatory port is already inside a git flow that has nothing sensible to do but
 * fail the request and let the caller retry.
 */
public class GitHostException extends RuntimeException {

  public GitHostException(String message) {
    super(message);
  }

  public GitHostException(String message, Throwable cause) {
    super(message, cause);
  }
}
