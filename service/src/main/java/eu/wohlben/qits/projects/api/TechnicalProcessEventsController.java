package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.projects.control.TechnicalProcessFrame;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The live narration of one technical process, as SSE — every connect replays the full story so
 * far and then streams. The controller the port's javadoc promised: it stands on
 * {@link TechnicalProcessRegistry} and knows nothing about what the process narrates.
 *
 * <p>An unknown or evicted id answers <b>404</b>, deliberately: {@code EventSource} treats a
 * non-200 as fatal, which the frontend reads as "expired — the outcome is on the row now". A ~25s
 * {@code ping} frame keeps a quiet segment from looking like a dead connection.
 */
@Path("/technical-processes/{id}/events")
@RolesAllowed("qits:admin")
public class TechnicalProcessEventsController {

  @Inject TechnicalProcessRegistry registry;

  @ConfigProperty(name = "qits.projects.process.heartbeat-ms", defaultValue = "25000")
  long heartbeatMs;

  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "technical-process-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<TechnicalProcessFrame> events(@PathParam("id") String id) {
    TechnicalProcess process =
        registry
            .find(id)
            .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("No such process"));
    return Multi.createFrom().emitter(emitter -> subscribe(process, emitter));
  }

  private void subscribe(TechnicalProcess process, MultiEmitter<? super TechnicalProcessFrame> emitter) {
    AtomicBoolean open = new AtomicBoolean(true);
    AtomicLong pingSeq = new AtomicLong(1_000_000_000L);
    TechnicalProcess.Listener listener =
        new TechnicalProcess.Listener() {
          @Override
          public void onFrame(TechnicalProcessFrame frame) {
            if (open.get()) {
              emitter.emit(frame);
            }
          }

          @Override
          public void onDone() {
            if (open.compareAndSet(true, false)) {
              emitter.complete();
            }
          }

          @Override
          public boolean isOpen() {
            return open.get();
          }
        };
    ScheduledFuture<?> heartbeat =
        heartbeats.scheduleAtFixedRate(
            () -> {
              if (open.get()) {
                emitter.emit(TechnicalProcessFrame.ping(pingSeq.incrementAndGet()));
              }
            },
            heartbeatMs,
            heartbeatMs,
            TimeUnit.MILLISECONDS);
    emitter.onTermination(
        () -> {
          open.set(false);
          heartbeat.cancel(false);
          process.detach(listener);
        });
    process.attach(listener);
  }
}
