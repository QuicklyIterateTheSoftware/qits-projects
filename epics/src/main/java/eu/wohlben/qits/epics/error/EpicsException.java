package eu.wohlben.qits.epics.error;

/**
 * Base for epics errors. Carries an HTTP-ish status code so the web layer can map it to a response
 * without epics depending on JAX-RS (the same framework-free stance as {@code domain.error} — but
 * epics must not depend on {@code domain}, so it owns its own). The {@code service} module maps
 * these via {@code EpicsExceptionMapper}.
 */
public class EpicsException extends RuntimeException {

  private final int statusCode;

  public EpicsException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public EpicsException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
