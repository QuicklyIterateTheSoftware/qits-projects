package eu.wohlben.qits.epics.error;

/** Epics error mapped to HTTP 404 by the web layer. */
public class NotFoundException extends EpicsException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
