package eu.wohlben.qits.projects.control;

import java.util.Collection;

/**
 * One tracked long-running operation, framed as a segmented, streamable narration so a UI can watch
 * {@code pull:root} → {@code pull:child} → {@code push:root} live instead of a spinner.
 *
 * <p>Part of the technical-process PORT (see {@link TechnicalProcessRegistry}): declared here,
 * implemented by the assembling application. Every method is a no-op-safe narration verb — the git
 * work is done by this context either way, and a null process means "run it unnarrated", which is
 * exactly what the non-streamed {@code RepositoryService.pullRepository(repoId)} overload already
 * passes.
 */
public interface TechnicalProcess {

  /** A subscriber to this process's frames; {@code attach} replays everything so far first. */
  interface Listener {

    void onFrame(TechnicalProcessFrame frame);

    void onDone();

    boolean isOpen();
  }

  /** The process id, as handed back to the caller of pull/push/sync. */
  String id();

  /** Whether the terminal {@code done} frame has already been emitted. */
  boolean isTerminal();

  /** Subscribe, replaying every frame emitted so far (plus {@code done} if already terminal). */
  void attach(Listener listener);

  /** Unsubscribe. */
  void detach(Listener listener);

  /** Open a segment; output appended afterwards belongs to it until it settles. */
  void openSegment(String name);

  /** Append one line of output to an open segment. */
  void appendLine(String segmentName, String line);

  /** Whether {@code segmentName} has already settled (a repeat settle is ignored). */
  boolean isSegmentSettled(String segmentName);

  /** Close a segment, successfully or not. */
  void settleSegment(String segmentName, boolean ok);

  /** Close a segment with a frontend hint (e.g. {@code remote-auth}) and its target. */
  void settleSegment(String segmentName, boolean ok, String hint, String hintTarget);

  /** Close a segment that had nothing to do, with the reason shown in its place. */
  void completeNoOp(String segmentName, String note);

  /**
   * Declare the asynchronous second phase this process still waits on. An empty collection means
   * there is none, so {@link #finishProvision} settles immediately — what every repository-scoped
   * operation here passes.
   */
  void expectServices(Collection<String> serviceNames);

  /** Terminal: the operation finished; {@code ok} is combined with any failed segment. */
  void finishProvision(boolean ok);

  /** Terminal: the operation failed, with the message shown to the user. */
  void failProvision(String message);

  /** Terminal failure carrying a frontend hint (e.g. {@code remote-auth}) and its target. */
  void failProvision(String message, String hint, String hintTarget);

  /** Terminal, unconditionally — the backstop for a process that never converges. */
  void forceFinish();
}
