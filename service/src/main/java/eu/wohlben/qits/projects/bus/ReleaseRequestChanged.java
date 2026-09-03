package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A release request's backing branch has a new tip: this repository, this request, this is the sha
 * that is now the whole of what it would release.
 *
 * <p>The <b>second</b> event this service publishes, and the one the release flow turns on. A
 * request is an octopus merge of N sources folded onto {@code release/<id>} — a branch nobody
 * pushed, written by qits-githost's merge primitive, which deliberately fires no {@code
 * post-receive} and therefore publishes no {@code SCMPublishCommit}. So without this event the fold
 * would exist and <b>nothing would build it</b>. qits-ci is the motivating consumer: it reads
 * {@code repoId} and {@code mergedSha} and runs the repository's pipeline against the fold, whose
 * verdict comes back here as the request's build gate.
 *
 * <p><b>Only a real change is announced.</b> A fold the git host answered {@code unchanged} — every
 * head already contained, same sha, no new commit — is not a change and publishes nothing: the
 * triggers that fire on a set that did not really move (a pending tag leaving the implicit set, a
 * duplicate delivery) are exactly the ones that produce it, and announcing them would ask for a
 * build of a sha already built. A fold that <b>conflicted</b> publishes nothing either: no ref moved
 * and there is no new sha to name.
 *
 * <p><b>It names things across a boundary the way the platform names them:</b> string ids, never a
 * reference into this context's tables. {@code repoId} is the git host's storage key and qits-ci's
 * own; {@code repoName} and {@code projectId} ride along for a consumer that shows a person what is
 * happening, and either may be null — a repository can have no alias and no project.
 *
 * <p><b>{@code backingBranch} is carried rather than derived.</b> It is {@code release/} +
 * {@code releaseRequestId} today, and a consumer could compose it — but the naming convention is
 * this service's to change and a consumer that composed it would silently break when it did.
 *
 * <p><b>{@code changedAt} is when the merge landed</b>, not the moment {@code publish()} was called
 * — the two differ by however long the announcement took to be made, and the one that belongs in an
 * event log is when the thing happened.
 *
 * <p><b>{@code eventId} is a component, and that is safe.</b> It is generated when absent and final
 * once set, which gives the stability the idempotent {@code PUT} rests on, and the library keeps
 * everything {@link QitsEvent} declares out of the canonical payload — so identity travels in the
 * envelope and the payload is the six fields below.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, the ruling {@link
 * RepositoryRenamed} states and for the same reason: a jar this platform's Maven registry does not
 * serve is a build that resolves from a developer's {@code ~/.m2} and fails in a release pipeline's
 * step container. A consumer decodes it with {@code CanonicalJson.payloadTo} into a local record of
 * its own — the {@code qits-deployments} subscriber shape, which is also how {@code
 * BuildStatusListener} in this very package reads qits-ci's verdicts. The field list below is the
 * contract qits-ci builds its local record against.
 *
 * <p>Registered for reflection in {@link EventWireReflection} — {@code CanonicalJson} builds its own
 * {@code ObjectMapper}, so nothing else can see that this record crosses the wire, and a native
 * image without the registration loses every announcement to a "no serializer found" inside the
 * publish.
 */
public record ReleaseRequestChanged(
    UUID eventId,
    String projectId,
    String repoId,
    String repoName,
    String releaseRequestId,
    String backingBranch,
    String mergedSha,
    Instant changedAt)
    implements QitsEvent {

  public ReleaseRequestChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public ReleaseRequestChanged(
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt) {
    this(null, projectId, repoId, repoName, releaseRequestId, backingBranch, mergedSha, changedAt);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
