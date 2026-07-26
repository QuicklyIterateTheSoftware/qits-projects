package eu.wohlben.qits.projects.control;

/**
 * One frame of a {@link TechnicalProcess}'s narration, as replayed to a subscriber.
 *
 * <p>Part of the technical-process PORT (see {@link TechnicalProcessRegistry}). The wire shape —
 * field names, {@code kind}/{@code status}/{@code hint} vocabularies — is the contract the frontend
 * already consumes, so it is reproduced here verbatim rather than re-invented.
 */
public record TechnicalProcessFrame(
    String segment,
    String kind,
    long seq,
    String line,
    String status,
    String hint,
    String hintTarget) {

  public static final String KIND_SEGMENT_OPEN = "segment-open";
  public static final String KIND_LINE = "line";
  public static final String KIND_SEGMENT_SETTLED = "segment-settled";
  public static final String KIND_DONE = "done";
  public static final String KIND_PING = "ping";

  public static final String STATUS_OK = "ok";
  public static final String STATUS_FAILED = "failed";

  /**
   * The frontend's cue to offer the remote-login flow: this failure was an authentication wall at
   * the remote, and {@code hintTarget} names the repository to sign in to.
   */
  public static final String HINT_REMOTE_AUTH = "remote-auth";

  public static TechnicalProcessFrame segmentOpen(String segment, long seq) {
    return new TechnicalProcessFrame(segment, KIND_SEGMENT_OPEN, seq, null, null, null, null);
  }

  public static TechnicalProcessFrame line(String segment, long seq, String line) {
    return new TechnicalProcessFrame(segment, KIND_LINE, seq, line, null, null, null);
  }

  public static TechnicalProcessFrame segmentSettled(String segment, long seq, boolean ok) {
    return segmentSettled(segment, seq, ok, null, null);
  }

  public static TechnicalProcessFrame segmentSettled(
      String segment, long seq, boolean ok, String hint, String hintTarget) {
    return new TechnicalProcessFrame(
        segment, KIND_SEGMENT_SETTLED, seq, null, ok ? STATUS_OK : STATUS_FAILED, hint, hintTarget);
  }

  public static TechnicalProcessFrame done(long seq, boolean ok) {
    return new TechnicalProcessFrame(
        null, KIND_DONE, seq, null, ok ? STATUS_OK : STATUS_FAILED, null, null);
  }

  public static TechnicalProcessFrame ping(long seq) {
    return new TechnicalProcessFrame(null, KIND_PING, seq, null, null, null, null);
  }
}
