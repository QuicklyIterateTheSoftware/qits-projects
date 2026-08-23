package eu.wohlben.qits.projects.refinementhost;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
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
 * The in-JVM fan-out from {@link RefinementChangeHint}s to per-refinement SSE streams — the same
 * shape as {@code api/ProjectEventBroadcaster} (leading-edge + trailing debounce per (row, topic),
 * hot ref-counted {@link BroadcastProcessor} per row, overflow dropped), keyed by refinement row id.
 */
@ApplicationScoped
public class RefinementEventBroadcaster {

  private static final Logger LOG = Logger.getLogger(RefinementEventBroadcaster.class);

  @ConfigProperty(name = "qits.projects.refinement.events.debounce-ms", defaultValue = "1000")
  long debounceMillis;

  private final Map<Long, BroadcastProcessor<String>> processors = new ConcurrentHashMap<>();
  private final Map<Long, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();

  private record DebounceKey(Long refinementId, RefinementChangeHint.Topic topic) {}

  private static final class Window {
    boolean trailing;
  }

  private final Map<DebounceKey, Window> windows = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "refinement-events-debounce");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /** The live topic stream for one refinement, hot and shared. */
  public Multi<String> subscribe(Long refinementId) {
    return Multi.createFrom()
        .deferred(
            () -> {
              BroadcastProcessor<String> processor =
                  processors.computeIfAbsent(refinementId, k -> BroadcastProcessor.create());
              subscriberCounts
                  .computeIfAbsent(refinementId, k -> new AtomicInteger())
                  .incrementAndGet();
              AtomicBoolean released = new AtomicBoolean();
              Runnable release =
                  () -> {
                    if (released.compareAndSet(false, true)) {
                      AtomicInteger count = subscriberCounts.get(refinementId);
                      if (count != null && count.decrementAndGet() <= 0) {
                        subscriberCounts.remove(refinementId);
                        processors.remove(refinementId);
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

  void onHint(@ObservesAsync RefinementChangeHint hint) {
    DebounceKey debounceKey = new DebounceKey(hint.refinementId(), hint.topic());
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
      emit(hint.refinementId(), hint.topic());
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
      emit(debounceKey.refinementId(), debounceKey.topic());
    }
  }

  private void emit(Long refinementId, RefinementChangeHint.Topic topic) {
    BroadcastProcessor<String> processor = processors.get(refinementId);
    if (processor == null) {
      return; // nobody watching — the hint self-heals on connect
    }
    try {
      // Wire name: PROMPT_DRAFT -> "prompt-draft", the topic contract the SPA already consumes.
      processor.onNext(topic.name().toLowerCase().replace('_', '-'));
    } catch (RuntimeException e) {
      LOG.debugf(e, "Dropped refinement hint %s for %s", topic, refinementId);
    }
  }

  /** Merge the ~25s SSE {@code ping} heartbeat into a hint stream. */
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
}
