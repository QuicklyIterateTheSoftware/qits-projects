package eu.wohlben.qits.projects.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * A branch moved, so <b>every open release request that names it as a source</b> re-folds onto its
 * own backing branch — the merge-request shape generalised to N participants: pushing a fix onto a
 * rejected request is still the ordinary way to answer it, and a source that outran a pending gate
 * must invalidate it rather than be silently released. A push to {@code main} therefore touches
 * every open request of the repository, and each is folded separately: a shared trigger is never a
 * shared merge. A branch <b>deleted</b> is dropped from the requests naming it, and one left with
 * nothing but the default branch is withdrawn: there is no work in it any more and the row would
 * otherwise stand open forever (three did, 2026-09-01).
 *
 * <p><b>This is the whole of how a push reaches the release flow.</b> The git host's merge primitive
 * fires no {@code post-receive}, so a backing branch's own movement never comes back here — which is
 * what keeps the loop from feeding itself.
 *
 * <p>Its own durable consumer beside {@link ScmBackupTriggerListener} rather than a second concern
 * inside it: the backup consumption is total over all four SCM events and must never learn release
 * semantics, and the two keep separate watermarks so one's poison cannot hold the other's.
 *
 * <p>{@code suppressCi} is deliberately ignored, the backup listener's reasoning pointed at gates:
 * a no-ci push still moves the head, so the request must still re-fold and re-arm — its gate then
 * passes through the settle window, because no verdict is coming. Reading the flag here would let a
 * no-ci push land commits nothing re-gated.
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
    return Set.of(
        SCMPublishCommit.class.getSimpleName(), SCMDeleteBranch.class.getSimpleName());
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
    if (SCMDeleteBranch.class.getSimpleName().equals(frame.name())) {
      if (isBlank(repoId) || isBlank(branch)) {
        LOG.warnf(
            "%s %s names no (repoId, branch); no request can drop a source on it",
            frame.name(), frame.id());
        return;
      }
      releaseRequests.onBranchDeleted(repoId, branch);
      return;
    }
    String sha = payload.path("sha").asText(null);
    if (isBlank(repoId) || isBlank(branch) || isBlank(sha)) {
      LOG.warnf(
          "%s %s names no (repoId, branch, sha); no request can re-fold on it",
          frame.name(), frame.id());
      return;
    }
    releaseRequests.onBranchMoved(repoId, branch, sha);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
