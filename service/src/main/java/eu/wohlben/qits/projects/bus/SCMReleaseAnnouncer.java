package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ReleaseAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a landed tag into {@link SCMRelease} and hands it to the bus — the <b>third</b> thing this
 * service publishes, beside {@code RepositoryRenamedAnnouncer} and {@code
 * ReleaseRequestChangedAnnouncer} and for the same structural reasons.
 *
 * <p>It lives in {@code service/} because {@code domain} knows nothing of the bus; the seam it
 * implements is {@link ReleaseAnnouncer} in {@code projects/control}, and zero implementations is a
 * supported configuration.
 *
 * <p><b>The cause is left to the bus.</b> {@code QitsEventBus.publish(event)} resolves the parent
 * from {@code CausationScope}, so a release made under the verdict consumption inherits that
 * frame's — which is the chain worth having: push → release request changed → CI run → verdict →
 * release → deploy.
 *
 * <p><b>Announced after the tag, never conditionally on anything after it.</b> The tag is
 * irreversible the instant qits-githost accepts it, so a statement gated on a branch delete, a
 * transaction or the request's own settling would be silent about a release that really happened.
 *
 * <p><b>{@code @DefaultBean}</b>, the posture every adapter here takes: the suite's recording fake
 * then wins the port's injection point simply by existing, so no test reaches the bus and none has
 * to arrange not to.
 */
@ApplicationScoped
@DefaultBean
public class SCMReleaseAnnouncer implements ReleaseAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onReleased(
      String projectId,
      String repoId,
      String repoName,
      String branch,
      String version,
      Instant occurredAt) {
    bus.publish(new SCMRelease(projectId, repoId, repoName, branch, version, occurredAt));
  }
}
