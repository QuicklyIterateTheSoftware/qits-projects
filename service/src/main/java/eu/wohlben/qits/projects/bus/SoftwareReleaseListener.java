package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A release was published, which is the terminal gate for a repository that deploys nothing.</b>
 * The non-deployable half of the publish phase, and a <b>temporary</b> one: {@link
 * ReleaseFinalization#onSoftwareRelease} carries the whole argument for why it exists and what
 * replaces it.
 *
 * <p>qits-ci publishes one {@code SoftwareRelease} per declared artifact, so a repository releasing
 * a jar, an npm package and an image announces three for one tag. That is not deduplicated here:
 * the first one decides, the rest find the gate already stamped and ask the git host nothing, which
 * is a property of the domain call and not of this seam.
 *
 * <p><b>The payload is a local record</b>, bound by {@link CanonicalJson} — qits-ci's {@code
 * ci-events} module is another context's vocabulary and this service depends on it nowhere, the
 * same answer {@code BuildStatusListener} gives for that repository's build verdicts and {@code
 * DeploymentActiveListener} gives for qits-deployments'.
 *
 * <h2>Failure</h2>
 *
 * <p>The seam's rule. A payload that will not parse, or one naming no repository or no version, is
 * poison — WARN and settle. Everything the domain call raises is left to throw, and there is exactly
 * one thing it raises: a git host that could not say whether the repository deploys anything. That
 * is a statement about the moment, the next catch-up asks again, and answering it wrongly would
 * either finalize {@code main} ahead of a deployment or never finalize it at all.
 *
 * <p>{@link #replayFromEpoch()} is left at its default — the head of the log. A brand-new consumer
 * replaying from the epoch would walk every artifact this platform has ever published and read a
 * tree per release for tags whose merges are long since irrelevant.
 */
@ApplicationScoped
public class SoftwareReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(SoftwareReleaseListener.class);

  /** qits-ci's "the package exists" — {@code SoftwareRelease}, as the wire spells it. */
  static final String SIGNATURE = "SoftwareRelease";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * <b>Never change it</b> — a new value is a brand-new consumer initializing at the head of the
   * log, silently skipping every release in between. It names the consumption, not the class, and
   * it is the one thing here that must outlive the temporary shortcut it serves.
   */
  static final String CONSUMER_ID = "projects-non-deployable-publish";

  /**
   * The fields this listener reads, as a local record bound by {@link CanonicalJson}.
   *
   * <p>{@code repoId} and {@code repository} carry <b>the same string</b> — qits-projects'
   * repository row id, which is also the git host's key for the bare — and qits-ci's own record says
   * so: {@code repository} is the name every existing consumer already selects on and {@code repoId}
   * is the platform's spelling of it, added beside rather than instead. Both are read here and the
   * first non-blank wins, so this works against a publisher on either side of that addition.
   *
   * <p>{@code packageName}/{@code packageType} are deliberately absent: which artifact of a release
   * announced is nothing to do with whether the repository deploys, and reading them would invite a
   * per-type rule that has no business existing here.
   */
  public record SoftwareReleasePayload(String repository, String repoId, String version) {}

  @Inject ReleaseFinalization finalization;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    SoftwareReleasePayload release = decode(frame);
    if (release == null) {
      // Warned in decode. Returning settles the event: the same bytes would fail identically on
      // every later offer, and an event nothing can read must not hold the watermark.
      return;
    }
    String repoId = isBlank(release.repoId()) ? release.repository() : release.repoId();
    if (isBlank(repoId) || isBlank(release.version())) {
      LOG.warnf(
          "%s %s names no (repository, version) to finalize a release with; it is skipped",
          frame.name(), frame.id());
      return;
    }
    // Throws only when the git host could not say whether this repository deploys anything, which
    // is exactly the case the next catch-up fixes.
    finalization.onSoftwareRelease(repoId.trim(), release.version());
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private SoftwareReleasePayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), SoftwareReleasePayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
