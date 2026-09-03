package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a landed fold into {@link ReleaseRequestChanged} and hands it to the bus — the second thing
 * this service publishes, beside {@code RepositoryRenamedAnnouncer} and for the same structural
 * reasons.
 *
 * <p>It lives in {@code service/} because {@code domain} knows nothing of the bus; the seam it
 * implements is {@link ReleaseRequestAnnouncer} in {@code projects/control}, and zero
 * implementations is a supported configuration (which is what {@code domain}'s own suite runs as).
 *
 * <p><b>The cause is left to the bus.</b> {@code QitsEventBus.publish(event)} resolves the parent
 * from {@code CausationScope}. A fold made on the request thread that created the request inherits
 * that request's cause; one made under the push consumption inherits the frame's — which is exactly
 * the chain worth having: push → release request changed → CI run → deploy.
 *
 * <p><b>The announcement is made AFTER the transaction that landed the fold</b>, never inside it —
 * the rule {@code RepositoryRenamedAnnouncer} states, and here it is doubly load-bearing because the
 * fold's write transaction is re-entered by the sweep.
 *
 * <p><b>{@code @DefaultBean}</b>, the posture every adapter here takes: the suite's recording fake
 * then wins the port's injection point simply by existing, so no test reaches the bus and none has
 * to arrange not to.
 */
@ApplicationScoped
@DefaultBean
public class ReleaseRequestChangedAnnouncer implements ReleaseRequestAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onReleaseRequestChanged(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt) {
    bus.publish(
        new ReleaseRequestChanged(
            projectId, repoId, repoName, releaseRequestId, backingBranch, mergedSha, changedAt));
  }
}
