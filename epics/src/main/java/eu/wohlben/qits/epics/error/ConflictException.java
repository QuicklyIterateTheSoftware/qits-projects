package eu.wohlben.qits.epics.error;

/**
 * Epics error mapped to HTTP 409 by the web layer: the request is well formed, but the epic's
 * status does not allow it — an illegal transition, or a mutation the current phase freezes.
 */
public class ConflictException extends EpicsException {

  public ConflictException(String message) {
    super(409, message);
  }
}
