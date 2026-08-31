package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BuildStatusLedger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * qits-ci said a run finished — green or red — so the per-commit build-status ledger gains a row.
 *
 * <p>The consuming half of the release-quality-gates foundation: {@link BuildStatusLedger} is what
 * a release request's build gate will read, and later this same consumption is where a pending gate
 * resolves — event arrives, ledger row written, matching gates re-evaluated, one consumer in one
 * service. That coupling is the reason the ledger is here and not in the git host.
 *
 * <p><b>The signatures are strings and the payload is a local record</b>, the {@code
 * qits-deployments} subscriber's shape rather than the githost-events one: qits-ci publishes no
 * consumed vocabulary jar this service already holds, and the payload is a handful of strings on a
 * wire. The cost — a rename over there is silent here — is the cost every cross-repo event contract
 * carries, and the listener test pins both names as literals so a change at least has to be a diff.
 *
 * <p><b>What never arrives is part of the contract:</b> qits-ci announces terminal runs only, and
 * neither cancelled nor deduped-superseded ones — so every row written here is a genuine verdict
 * about a commit, and a commit with rows for every run and no failures among them is what the build
 * gate will read as green.
 *
 * <h2>Failure</h2>
 *
 * <p>The seam's rule: a throw rolls the claim back and the event is owed forever, so swallow what
 * retrying cannot fix and throw what it can. A payload that will not parse or names no {@code
 * (runId, repoId, commitSha)} is poison — WARN and return. A database that could not answer is left
 * to throw, because the next attempt is exactly what fixes it.
 */
@ApplicationScoped
public class BuildStatusListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(BuildStatusListener.class);

  /** qits-ci's green verdict — {@code BuildSuccessful}'s simple name, as the wire spells it. */
  static final String SUCCESS_SIGNATURE = "BuildSuccessful";

  /** qits-ci's red verdict — {@code BuildFailed}, whose {@code outcome} says which kind of red. */
  static final String FAILURE_SIGNATURE = "BuildFailed";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * <b>Never change it</b> — a new value is a brand-new consumer initializing at the head of the
   * log, silently skipping everything in between. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = "projects-build-status";

  /**
   * The fields this ledger stores. {@code outcome} exists only on {@code BuildFailed}; {@code
   * finishedAt} is deliberately not bound — it is the envelope's {@code occurredAt}, the log's own
   * ordering key, read off the frame. Unknown fields are ignored by the library's mapper, which is
   * what lets qits-ci add one.
   */
  public record BuildVerdictPayload(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String outcome) {}

  @Inject BuildStatusLedger ledger;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SUCCESS_SIGNATURE, FAILURE_SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    BuildVerdictPayload build = decode(frame);
    if (build == null) {
      // Warned in decode. Returning settles the event: the same bytes would fail identically on
      // every later offer, and an event nothing can read must not hold the watermark.
      return;
    }
    if (isBlank(build.runId()) || isBlank(build.repoId()) || isBlank(build.commitSha())) {
      LOG.warnf(
          "%s %s carries no (runId, repoId, commitSha) to record a verdict under; it is skipped",
          frame.name(), frame.id());
      return;
    }
    ledger.record(
        new BuildStatusLedger.Verdict(
            build.runId(),
            build.repoId(),
            build.projectId(),
            build.repoName(),
            build.branch(),
            build.commitSha(),
            statusOf(frame, build),
            frame.occurredAt(),
            causeOf(frame)));
  }

  /**
   * The word the row records: {@code SUCCESS} for the green event, the failure's own {@code
   * outcome} otherwise — and {@code FAILED} when a red event carries none, so an older publisher's
   * announcement still lands as a verdict rather than as poison.
   */
  private static String statusOf(EventFrame frame, BuildVerdictPayload build) {
    if (SUCCESS_SIGNATURE.equals(frame.name())) {
      return "SUCCESS";
    }
    return isBlank(build.outcome()) ? "FAILED" : build.outcome();
  }

  /**
   * This frame as the row's cause. Lenient — an id that is not a UUID costs the trace edge and
   * nothing else, because causation must never be able to refuse a verdict.
   */
  private static UUID causeOf(EventFrame frame) {
    if (frame.id() == null) {
      return null;
    }
    try {
      return UUID.fromString(frame.id());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private BuildVerdictPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), BuildVerdictPayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
