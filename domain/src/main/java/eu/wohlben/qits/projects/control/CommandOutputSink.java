package eu.wohlben.qits.projects.control;

/**
 * A destination for a terminal session's output — the remote-login session fans every chunk of PTY
 * output out to all attached sinks. Kept framework-free (no websockets.next type) so the session
 * can live in the domain module; the service module's websocket adapts a connection to a sink.
 *
 * <p>A migration-plan §5 duplicate. The monorepo declares this as {@code
 * eu.wohlben.qits.domain.command.control.CommandOutputSink}, in the command context that belongs to
 * qits-workspace-daemon. It is a two-method, dependency-free SPI, so copying it is cheaper and
 * looser than depending on the daemon jar for it — the same call the workspaces extraction made for
 * the daemon SPIs it re-declares. Consolidate into libs/qits-commons when that exists.
 */
public interface CommandOutputSink {

  /**
   * Forward a chunk of already terminal-encoded output to the client (written verbatim to xterm).
   */
  void write(String data);

  /** Whether this sink can still receive output; the sender prunes sinks that report false. */
  boolean isOpen();
}
