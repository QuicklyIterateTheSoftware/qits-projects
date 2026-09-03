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
 * <b>A deployment is live, so the release it deployed reaches {@code main}.</b> The consuming half
 * of the publish phase, and the platform's <em>first</em> consumer of the deployment lifecycle
 * events: qits-deployments has announced them since it was written and nothing has ever subscribed.
 *
 * <p>What it does is one call — {@link ReleaseFinalization#onDeploymentActive} — and every decision
 * behind it (which release a version is, whether the merge is owed, what a failure means) is stated
 * there. This class is the seam: decode, check that the two fields the correlation needs are there,
 * hand over.
 *
 * <h2>The payload is a LOCAL record, and that is a decision rather than a shortcut</h2>
 *
 * <p>qits-deployments publishes a vocabulary jar — {@code qits-platform-deployments-events}, which
 * carries the real {@code DeploymentActive} — and this service does not depend on it, for the
 * reason this repository's first rule gives: a clone builds and tests green on its own, and a jar
 * the platform's Maven registry does not serve is a build that resolves out of somebody's {@code
 * ~/.m2} and fails in a release pipeline's step container. That registry serves <b>nothing</b> under
 * that coordinate today (measured 2026-09-03), and the {@code version} field this listener is
 * entirely about is newer still. So the payload is bound into a local record by {@link
 * CanonicalJson}, the platform's standing answer for a cross-repo event — {@code BuildStatusListener}
 * does it for qits-ci's verdicts one file over.
 *
 * <p>The cost is honest and is the cost every cross-repo contract carries: a rename over there is
 * silent here. What makes it survivable is that the wire name is a signature string on both sides
 * and the fields are named in one place, so a change at least has to be a diff.
 *
 * <h2>Failure</h2>
 *
 * <p>The seam's rule — a throw rolls the claim back and the event is owed forever, so swallow what
 * retrying cannot fix and throw what it can — with one thing worth saying plainly: <b>a merge that
 * did not apply is not thrown here.</b> It is recorded on the released tag's own row and retried by
 * {@code ReleaseFinalization}'s sweep, because throwing would hold this consumer's watermark behind
 * one repository's stuck merge and every other application's deployment would stop being read.
 *
 * <p>So: a payload that will not parse, or one naming no version, is poison — WARN and settle. A
 * database that could not answer is left to throw, because the next attempt is exactly what fixes
 * it.
 *
 * <h2>Where it starts reading</h2>
 *
 * <p>{@link #replayFromEpoch()} is left at its default, which is the head of the log, and it is a
 * choice rather than an omission: a brand-new consumer replaying from the epoch would walk every
 * deployment this platform has ever made and try to merge tags whose releases are ancient history.
 * "From now on, a deployment finalizes main" is exactly the semantics wanted.
 */
@ApplicationScoped
public class DeploymentActiveListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(DeploymentActiveListener.class);

  /** qits-deployments' "this is what serves now" — {@code DeploymentActive}, as the wire spells it. */
  static final String SIGNATURE = "DeploymentActive";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * <b>Never change it</b> — a new value is a brand-new consumer initializing at the head of the
   * log, silently skipping every deployment in between. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = "projects-main-finalization";

  /**
   * The fields this listener reads, as a local record bound by {@link CanonicalJson}. Unknown fields
   * are ignored by the library's mapper, which is what lets qits-deployments add one — and it has:
   * {@code containerName}, {@code endpoints}, {@code navigation}, {@code browserHost} and {@code
   * apiDocsPath} all travel and none of them are anything to do with finalizing {@code main}.
   *
   * <p><b>{@code version} is the whole correlation.</b> It is the released coordinate — the calver
   * qits-projects stamped, the tag it created, the image tag the deployment pulled — and it is what
   * a pending released tag is looked up by. {@code commitSha} is deliberately NOT used for that: the
   * sha a deployment records is the tag's commit as the deployer resolved it, while the row here
   * records the version-bump commit the release actually tagged, and the two need not be spelled
   * the same string.
   *
   * <p>{@code eventId} and {@code occurredAt}/{@code finishedAt} are absent on purpose: the first is
   * {@code QitsEvent}'s own accessor, which the canonical mix-in keeps out of every payload, and the
   * second is read off the frame where it is ever needed.
   *
   * <p>Public so {@code bus/EventWireReflection} and the wire test can name it.
   */
  public record DeploymentActivePayload(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha) {}

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
    DeploymentActivePayload deployment = decode(frame);
    if (deployment == null) {
      // Warned in decode. Returning settles the event: the same bytes would fail identically on
      // every later offer, and an event nothing can read must not hold the watermark.
      return;
    }
    if (isBlank(deployment.version())) {
      // Every deployment predating the version field lands here, and so would a deployer that
      // stopped sending it. It is a real fact about the platform and not this consumer's poison,
      // which is why it is a WARN naming the application rather than an exception.
      LOG.warnf(
          "%s %s carries no version, so no released tag can be finalized for %s; it is skipped",
          frame.name(), frame.id(), deployment.applicationName());
      return;
    }
    finalization.onDeploymentActive(
        deployment.applicationName(), deployment.version(), deployment.environmentName());
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private DeploymentActivePayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), DeploymentActivePayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
