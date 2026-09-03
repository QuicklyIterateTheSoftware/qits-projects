package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Source control has this release.</b> This version of this repository is tagged.
 *
 * <p><b>The publisher moved and the payload did not.</b> qits-workspaces announced this the instant
 * its release push was accepted, because only the process that ran {@code git push} knew atomically
 * that the push had succeeded and with which version. In the tag-only release flow there is no such
 * push: a release is a tag, asked for by <em>this</em> service through qits-githost's tag primitive,
 * and the equivalent moment is that primitive answering {@code 201}. So this record is a
 * <b>field-for-field replica</b> of {@code eu.wohlben.qits.workspaces.events.SCMRelease} — same
 * signature (the wire name is the simple class name), same five payload fields, same order — and it
 * has to stay one. Every existing consumer selects on those fields and none of them should have to
 * learn that the producer changed.
 *
 * <p><b>It does not mean an artifact exists.</b> Nothing is built, published or installable at this
 * moment — that statement is qits-ci's {@code SoftwareRelease}, emitted once per artifact when a
 * repository's release pipeline goes green. Between the two sits that pipeline, which each
 * repository owns. A consumer reading this as "the package is in the registry" is reading it wrong,
 * and the gap it would race against is a whole upstream build. That gap is why the event is named
 * for the SCM.
 *
 * <p><b>There is no target field, deliberately.</b> {@code branch} is the <b>source</b> branch that
 * was released — here the release request's backing branch, {@code release/<id>}, which is what the
 * fold lives on and therefore what was released. A release no longer lands on the default branch at
 * all: {@code main} is finalized after the deployment.
 *
 * <p><b>{@code repositoryName} is what a committed selection can address, and {@code repository}
 * cannot.</b> A repository's row id is whatever its registry minted: for a repository the platform
 * manifest declares it happens to equal the name, but a repository registered by the projects
 * self-seed reconcile gets a UUID — minted per platform instance, different on every one. A CI
 * trigger matching {@code repository: { exact: qits-projects-daemon }} therefore matched nothing on
 * a platform where that repository is a UUID row, and matched silently: CI logs matches, never
 * non-matches, so those release pipelines simply never fired. The name is the stable coordinate, so
 * the event carries both — the id for anyone joining back to the registry, the name for anyone
 * selecting on it. Nullable: a repository with no alias costs the event a field, never the release.
 *
 * <p><b>{@code eventId} and {@code occurredAt} are components and stay out of the payload.</b> The
 * library's canonical serializer excludes everything {@link QitsEvent} declares, and these two
 * accessors are those declarations — so identity and time travel in the envelope and the payload is
 * exactly the five fields {@code branch}, {@code projectId}, {@code repository}, {@code
 * repositoryName}, {@code version}. Reading a payload back therefore yields a fresh id and a null
 * time, which is correct: a received event's identity and clock are the envelope's.
 *
 * <p>It lives in {@code service/…/bus/} rather than a published vocabulary module, the {@link
 * RepositoryRenamed} ruling, and is registered in {@link EventWireReflection} — {@code CanonicalJson}
 * builds its own {@code ObjectMapper}, so an unregistered payload is every announcement dying inside
 * the publish on the native binary while the JVM suite stays green.
 *
 * @param projectId the project the repository belongs to, as qits-projects names it
 * @param repository the repository that released, by string id — never a reference into another
 *     context's tables
 * @param repositoryName the same repository by its registered name, the coordinate a committed CI
 *     selection can carry
 * @param branch the SOURCE branch that was released
 * @param version the release stamp, {@code YYYY.MMDD.HHMMSS} — also the name of the tag
 * @param occurredAt when the tag was accepted, which is when the release happened
 */
public record SCMRelease(
    UUID eventId,
    String projectId,
    String repository,
    String repositoryName,
    String branch,
    String version,
    Instant occurredAt)
    implements QitsEvent {

  public SCMRelease {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SCMRelease(
      String projectId,
      String repository,
      String repositoryName,
      String branch,
      String version,
      Instant occurredAt) {
    this(null, projectId, repository, repositoryName, branch, version, occurredAt);
  }
}
