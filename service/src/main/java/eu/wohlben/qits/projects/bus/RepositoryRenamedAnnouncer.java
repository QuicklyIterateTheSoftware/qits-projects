package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.RepositoryAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a rename into the platform's {@link RepositoryRenamed} event and hands it to the bus — the
 * producing end of this service's event-bus wiring, and the whole of it.
 *
 * <p>It lives in {@code service/} because {@code domain} knows nothing of the bus; the seam it
 * implements is {@link RepositoryAnnouncer} in {@code projects/control}, and zero implementations is
 * a supported configuration (which is what {@code domain}'s own suite runs as).
 *
 * <p><b>The cause is left to the bus.</b> {@code QitsEventBus.publish(event)} resolves the parent
 * from {@code CausationScope}, which the REST filter has already restored from the request's {@code
 * X-Qits-Causation-Id} — and a rename is made on the request thread, with no hop in between, so
 * there is nothing this class knows that the ambient scope does not. The explicit-parent overload
 * exists for a publish behind a thread hop; this is not one.
 *
 * <p><b>It blocks, briefly, and that was the trade.</b> {@code publish} attempts the PUT inline,
 * never throws, and gives up after the publish timeout — after which the outbox owns delivery. So a
 * qits-events that is down costs a rename a few seconds once and never fails it.
 *
 * <p><b>{@code @DefaultBean}</b>, the posture every adapter here takes ({@code
 * HttpGitHostRepositories}, {@code ContainersAgentRuntime}, {@code IdpAgentCredentials}): the suite's
 * {@code RecordingRepositoryAnnouncer} then wins the port's injection point simply by existing, so
 * no test reaches the bus and none has to arrange not to.
 */
@ApplicationScoped
@DefaultBean
public class RepositoryRenamedAnnouncer implements RepositoryAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onRepositoryRenamed(
      String projectId, String repositoryId, String oldName, String newName, Instant renamedAt) {
    bus.publish(new RepositoryRenamed(projectId, repositoryId, oldName, newName, renamedAt));
  }
}
