package eu.wohlben.qits.projects.processhost;

import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.projects.control.TechnicalProcessFrame;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One in-memory technical process: a segmented replay buffer plus live listeners — the
 * implementation of the domain's {@link TechnicalProcess} port, condensed from qits-workspaces'
 * class of the same purpose.
 *
 * <p><b>Attach replays everything so far, then goes live.</b> That is the whole contract the SSE
 * client relies on: every connect gets the full story with fresh ordering, {@code seq} orders one
 * connection only and is never a resume token.
 *
 * <p><b>Bounded.</b> A segment keeps its first and its most recent lines with an elision marker
 * between them, so a chatty clone cannot grow the heap; the bound is lines rather than the
 * reference's bytes because every producer here emits short lines.
 */
final class LiveTechnicalProcess implements TechnicalProcess {

  /** How many lines a segment keeps at each end of the elision. */
  private static final int HEAD_LINES = 200;

  private static final int TAIL_LINES = 600;

  private final String id;
  private final AtomicLong seq = new AtomicLong();
  private final Set<Listener> listeners = ConcurrentHashMap.newKeySet();
  private final Runnable onDone;

  private static final class Segment {
    final List<String> head = new ArrayList<>();
    final List<String> tail = new ArrayList<>();
    long elided;
    boolean settled;
    boolean ok;
    String hint;
    String hintTarget;
  }

  private final Map<String, Segment> segments = new LinkedHashMap<>();
  private boolean terminal;
  private boolean terminalOk;
  private Collection<String> expectedServices = List.of();

  LiveTechnicalProcess(String id, Runnable onDone) {
    this.id = id;
    this.onDone = onDone;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public synchronized boolean isTerminal() {
    return terminal;
  }

  @Override
  public void attach(Listener listener) {
    synchronized (this) {
      for (Map.Entry<String, Segment> entry : segments.entrySet()) {
        String name = entry.getKey();
        Segment segment = entry.getValue();
        listener.onFrame(TechnicalProcessFrame.segmentOpen(name, seq.incrementAndGet()));
        for (String line : segment.head) {
          listener.onFrame(TechnicalProcessFrame.line(name, seq.incrementAndGet(), line));
        }
        if (segment.elided > 0) {
          listener.onFrame(
              TechnicalProcessFrame.line(
                  name, seq.incrementAndGet(), "… " + segment.elided + " earlier lines elided …"));
        }
        for (String line : segment.tail) {
          listener.onFrame(TechnicalProcessFrame.line(name, seq.incrementAndGet(), line));
        }
        if (segment.settled) {
          listener.onFrame(
              TechnicalProcessFrame.segmentSettled(
                  name, seq.incrementAndGet(), segment.ok, segment.hint, segment.hintTarget));
        }
      }
      if (terminal) {
        listener.onFrame(TechnicalProcessFrame.done(seq.incrementAndGet(), terminalOk));
        listener.onDone();
        return;
      }
      listeners.add(listener);
    }
  }

  @Override
  public void detach(Listener listener) {
    listeners.remove(listener);
  }

  @Override
  public synchronized void openSegment(String name) {
    if (terminal || segments.containsKey(name)) {
      return;
    }
    segments.put(name, new Segment());
    broadcast(TechnicalProcessFrame.segmentOpen(name, seq.incrementAndGet()));
  }

  @Override
  public synchronized void appendLine(String segmentName, String line) {
    Segment segment = segments.get(segmentName);
    if (segment == null || segment.settled || terminal || line == null) {
      return;
    }
    if (segment.head.size() < HEAD_LINES) {
      segment.head.add(line);
    } else {
      segment.tail.add(line);
      if (segment.tail.size() > TAIL_LINES) {
        segment.tail.remove(0);
        segment.elided++;
      }
    }
    broadcast(TechnicalProcessFrame.line(segmentName, seq.incrementAndGet(), line));
  }

  @Override
  public synchronized boolean isSegmentSettled(String segmentName) {
    Segment segment = segments.get(segmentName);
    return segment != null && segment.settled;
  }

  @Override
  public void settleSegment(String segmentName, boolean ok) {
    settleSegment(segmentName, ok, null, null);
  }

  @Override
  public synchronized void settleSegment(
      String segmentName, boolean ok, String hint, String hintTarget) {
    Segment segment = segments.get(segmentName);
    if (segment == null || segment.settled) {
      return;
    }
    segment.settled = true;
    segment.ok = ok;
    segment.hint = hint;
    segment.hintTarget = hintTarget;
    broadcast(
        TechnicalProcessFrame.segmentSettled(
            segmentName, seq.incrementAndGet(), ok, hint, hintTarget));
  }

  @Override
  public synchronized void completeNoOp(String segmentName, String note) {
    openSegment(segmentName);
    appendLine(segmentName, note);
    settleSegment(segmentName, true);
  }

  @Override
  public synchronized void expectServices(Collection<String> serviceNames) {
    expectedServices = serviceNames == null ? List.of() : List.copyOf(serviceNames);
  }

  @Override
  public synchronized void finishProvision(boolean ok) {
    // Nothing here declares a second phase (expectServices is always empty on this service's
    // processes), so a finish settles immediately.
    finish(ok && segments.values().stream().noneMatch(segment -> segment.settled && !segment.ok));
  }

  @Override
  public void failProvision(String message) {
    failProvision(message, null, null);
  }

  @Override
  public synchronized void failProvision(String message, String hint, String hintTarget) {
    String name = segments.isEmpty() ? "failure" : lastSegmentName();
    openSegment(name);
    if (message != null && !message.isBlank()) {
      appendLine(name, message);
    }
    settleSegment(name, false, hint, hintTarget);
    finish(false);
  }

  @Override
  public synchronized void forceFinish() {
    finish(false);
  }

  private synchronized void finish(boolean ok) {
    if (terminal) {
      return;
    }
    terminal = true;
    terminalOk = ok;
    broadcast(TechnicalProcessFrame.done(seq.incrementAndGet(), ok));
    for (Listener listener : listeners) {
      try {
        listener.onDone();
      } catch (RuntimeException ignored) {
        // a dead listener must not keep the process from settling
      }
    }
    listeners.clear();
    onDone.run();
  }

  private String lastSegmentName() {
    String last = null;
    for (String name : segments.keySet()) {
      last = name;
    }
    return last;
  }

  private void broadcast(TechnicalProcessFrame frame) {
    for (Listener listener : listeners) {
      try {
        if (listener.isOpen()) {
          listener.onFrame(frame);
        } else {
          listeners.remove(listener);
        }
      } catch (RuntimeException ignored) {
        listeners.remove(listener);
      }
    }
  }
}
