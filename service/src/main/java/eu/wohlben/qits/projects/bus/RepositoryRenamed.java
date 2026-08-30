package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A repository answers to a new public name: this project, this repository, this was its name and
 * this is its name now.
 *
 * <p>The <b>first</b> event this service publishes. {@code service/…/bus/} was consume-only until
 * the rename endpoint landed, and the reason it is not any more is that a rename is the one thing
 * this service knows that nothing else can derive: the bare on the git host did not move — it is
 * keyed by {@code repositoryId}, which is unchanged — so a consumer holding a stale {@code
 * (projectId, repoName)} pair has no way to notice on its own. qits-ci is the motivating one: it
 * keys runs by storage id with the name as nullable metadata, so new pushes carry the new name by
 * themselves and a sweep of the old rows is a nice-to-have this event makes possible.
 *
 * <p><b>It names things across a boundary the way the platform names them:</b> string ids, never a
 * reference into this context's tables. {@code projectId} travels because a name is unique per
 * project and meaningless without one.
 *
 * <p><b>{@code occurredAt} is when the rename committed</b>, not the moment {@code publish()} was
 * called — the two differ by however long the announcement took to be made, and the one that belongs
 * in an event log is when the thing happened.
 *
 * <p><b>{@code eventId} is a component, and that is safe.</b> It is generated when absent and final
 * once set, which gives the stability the idempotent {@code PUT} rests on, and the library keeps
 * everything {@link QitsEvent} declares out of the canonical payload — so identity travels in the
 * envelope and the payload is the four fields below.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, deliberately: nothing
 * consumes it yet, and a jar this platform's Maven registry does not serve is a build that resolves
 * from a developer's {@code ~/.m2} and fails in a release pipeline's step container. A consumer
 * decodes it with {@code CanonicalJson.payloadTo} into a local record of its own, which is the
 * platform's standing answer. Publishing this as a jar is a decision to make when a second repo
 * needs the type, not before.
 *
 * <p>Registered for reflection in {@link EventWireReflection} — {@code CanonicalJson} builds its own
 * {@code ObjectMapper}, so nothing else can see that this record crosses the wire, and a native
 * image without the registration loses every announcement to a "no serializer found" inside the
 * publish.
 */
public record RepositoryRenamed(
    UUID eventId,
    String projectId,
    String repositoryId,
    String oldName,
    String newName,
    Instant renamedAt)
    implements QitsEvent {

  public RepositoryRenamed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public RepositoryRenamed(
      String projectId, String repositoryId, String oldName, String newName, Instant renamedAt) {
    this(null, projectId, repositoryId, oldName, newName, renamedAt);
  }

  @Override
  public Instant occurredAt() {
    return renamedAt;
  }
}
