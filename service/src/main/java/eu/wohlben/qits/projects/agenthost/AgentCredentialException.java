package eu.wohlben.qits.projects.agenthost;

/**
 * A credential could not be commissioned.
 *
 * <p><b>A plain {@link RuntimeException} and deliberately not a
 * {@code eu.wohlben.qits.projects.error.DomainException}.</b> {@code AgentContainers.ensure} rethrows
 * a {@code DomainException} with its status intact — that arm is for refusals, which are answers —
 * and catches everything else into {@code FAILED} with the message on {@code failureDetail}. This is
 * not an answer about the request: the idp could not be reached, or refused this service's own
 * credential, and the panel should show it the way it shows a provision that failed. So it must
 * arrive as the second kind.
 *
 * <p>{@link #retryable} is what the patient loop reads: an answer about the moment (nothing
 * answering, 401, 403, a 5xx) is asked again inside the window; an answer about the request is one
 * attempt, because no window fixes it.
 */
public class AgentCredentialException extends RuntimeException {

  private final boolean retryable;

  public AgentCredentialException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public AgentCredentialException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  /** Whether another attempt inside the patience window could answer differently. */
  public boolean retryable() {
    return retryable;
  }
}
