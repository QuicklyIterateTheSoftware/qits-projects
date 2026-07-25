package eu.wohlben.qits.epics.error;

/** Epics error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends EpicsException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
