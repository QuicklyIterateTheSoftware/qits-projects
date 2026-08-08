package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ProjectService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-JVM fan-out from {@link ProjectChangeHint}s (fired over CDI async events by {@link
 * ProjectChangePublisher}) to per-project SSE streams — a copy of qits-workspaces'
 * {@code WorkspaceEventBroadcaster}, narrowed to one scope key. Each subscribed project page gets a
 * {@link BroadcastProcessor}; the {@code @ObservesAsync} observer routes each hint — after a
 * per-(project, topic) debounce — into the matching processor as a lowercase topic string, which
 * {@link ProjectEventsController} emits as an SSE {@code data:} frame.
 *
 * <p>Debounce is leading-edge + trailing: the first hint in a quiet window emits immediately (a
 * proposed epic appears at once), further hints during the {@code qits.projects.events.debounce-ms}
 * window coalesce into at most one trailing emit, so an agent writing a whole feature/task tree in
 * a burst converges to ≤1 emit/s per topic instead of one per row. A missed or dropped hint
 * self-heals: the frontend re-fetches on the next hint or on reconnect, so overflow is simply
 * dropped.
 */
@ApplicationScoped
public class ProjectEventBroadcaster {

  private static final Logger LOG = Logger.getLogger(ProjectEventBroadcaster.class);

  @Inject ProjectService projectService;

  @ConfigProperty(name = "qits.projects.events.debounce-ms", defaultValue = "1000")
  long debounceMillis;

  private final Map<String, BroadcastProcessor<String>> processors = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();

  private record DebounceKey(String projectId, ProjectChangeHint.Topic topic) {}

  /** Open while a debounce window runs; {@code trailing} records a hint arrived mid-window. */
  private static final class Window {
    boolean trailing;
  }

  private final Map<DebounceKey, Window> windows = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "project-events-debounce");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * The live topic stream for one project, hot and shared: created on first subscriber, dropped on
   * last cancellation. The project is resolved first purely to 404 an unknown id — the channel key
   * is the id itself.
   */
  public Multi<String> subscribeToProject(String projectId) {
    projectService.get(projectId);
    return subscribe(projectId);
  }

  /** The channel for a project id, without the existence check. */
  public Multi<String> subscribe(String projectId) {
    return Multi.createFrom()
        .deferred(
            () -> {
              BroadcastProcessor<String> processor =
                  processors.computeIfAbsent(projectId, k -> BroadcastProcessor.create());
              subscriberCounts
                  .computeIfAbsent(projectId, k -> new AtomicInteger())
                  .incrementAndGet();
              AtomicBoolean released = new AtomicBoolean();
              Runnable release =
                  () -> {
                    if (released.compareAndSet(false, true)) {
                      AtomicInteger count = subscriberCounts.get(projectId);
                      if (count != null && count.decrementAndGet() <= 0) {
                        subscriberCounts.remove(projectId);
                        processors.remove(projectId);
                      }
                    }
                  };
              return processor
                  .onOverflow()
                  .drop()
                  .onCancellation()
                  .invoke(release)
                  .onTermination()
                  .invoke(release);
            });
  }

  /** Routes a fired hint into its project's stream, through the debounce gate. */
  void onHint(@ObservesAsync ProjectChangeHint hint) {
    DebounceKey debounceKey = new DebounceKey(hint.projectId(), hint.topic());
    boolean emitNow = false;
    synchronized (windows) {
      Window window = windows.get(debounceKey);
      if (window == null) {
        windows.put(debounceKey, new Window());
        emitNow = true;
        scheduler.schedule(() -> closeWindow(debounceKey), debounceMillis, TimeUnit.MILLISECONDS);
      } else {
        window.trailing = true;
      }
    }
    if (emitNow) {
      emit(hint.projectId(), hint.topic());
    }
  }

  private void closeWindow(DebounceKey debounceKey) {
    boolean emitTrailing = false;
    synchronized (windows) {
      Window window = windows.get(debounceKey);
      if (window != null && window.trailing) {
        window.trailing = false;
        emitTrailing = true;
        scheduler.schedule(() -> closeWindow(debounceKey), debounceMillis, TimeUnit.MILLISECONDS);
      } else {
        windows.remove(debounceKey);
      }
    }
    if (emitTrailing) {
      emit(debounceKey.projectId(), debounceKey.topic());
    }
  }

  private void emit(String projectId, ProjectChangeHint.Topic topic) {
    BroadcastProcessor<String> processor = processors.get(projectId);
    if (processor == null) {
      return; // nobody watching this project — the hint self-heals on connect
    }
    try {
      // Wire name: AGENT_ACTIVITY -> "agent-activity" (the frontend's topic contract).
      processor.onNext(topic.name().toLowerCase().replace('_', '-'));
    } catch (RuntimeException e) {
      LOG.debugf(e, "Dropped project hint %s for %s", topic, projectId);
    }
  }

  /**
   * Merge the ~25s SSE {@code ping} heartbeat into a hint stream, so an idle connection survives
   * the dev proxies (the frontend ignores the {@code ping} topic). {@code EventSource} reconnects
   * on its own, so no replay protocol is needed.
   */
  public Multi<String> withHeartbeat(Multi<String> hints) {
    Multi<String> heartbeat =
        Multi.createFrom()
            .ticks()
            .every(Duration.ofSeconds(25))
            .onOverflow()
            .drop()
            .map(tick -> "ping");
    return Multi.createBy().merging().streams(hints, heartbeat);
  }

  /** Test seam: how many project channels currently have at least one subscriber. */
  int openChannelCount() {
    return processors.size();
  }
}
