package eu.wohlben.qits.projects.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * A branch moved, so the open release request tracking it re-arms onto the new head — the
 * merge-request shape: pushing a fix onto a rejected request is the ordinary way to answer it, and
 * a head that outran a pending gate must invalidate it rather than be silently released.
 *
 * <p>Its own durable consumer beside {@link ScmBackupTriggerListener} rather than a second concern
 * inside it: the backup consumption is total over all four SCM events and must never learn release
 * semantics, and the two keep separate watermarks so one's poison cannot hold the other's.
 *
 * <p>{@code suppressCi} is deliberately ignored, the backup listener's reasoning pointed at gates:
 * a no-ci push still moves the head, so the open request must still re-arm — its gate then passes
 * through the settle window, because no verdict is coming. Reading the flag here would let a no-ci
 * push land commits nothing re-gated.
 *
 * <p>Failure policy is the seam's: an unreadable payload or one naming no {@code (repoId, branch,
 * sha)} is poison — WARN and settle; a database that could not answer is left to throw.
 */
@ApplicationScoped
public class ReleaseRequestHeadListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleaseRequestHeadListener.class);

  /** Storage, never a label — the {@code consumed_event}/watermark key. Never change it. */
  static final String CONSUMER_ID = "projects-release-request-heads";

  /** Its own mapper and only {@code readTree}, the backup listener's technique for its reason. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject ReleaseRequests releaseRequests;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SCMPublishCommit.class.getSimpleName());
  }

  @Override
  public void onFrame(EventFrame frame) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(frame.payload());
    } catch (Exception unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s",
          frame.name(), frame.id(), unreadable.toString());
      return;
    }
    String repoId = payload.path("repoId").asText(null);
    String branch = payload.path("branch").asText(null);
    String sha = payload.path("sha").asText(null);
    if (isBlank(repoId) || isBlank(branch) || isBlank(sha)) {
      LOG.warnf(
          "%s %s names no (repoId, branch, sha); no request can re-arm on it",
          frame.name(), frame.id());
      return;
    }
    releaseRequests.onBranchMoved(repoId, branch, sha);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
