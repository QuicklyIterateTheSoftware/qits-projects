package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus binds to and from JSON, told to native-image. No code, no bean, nothing at
 * runtime: the annotation is the entire content, and this class exists so that the annotation has
 * somewhere to live that can say why.
 *
 * <p><b>Why nothing registers these automatically.</b> Quarkus registers reflection for the classes
 * <em>it</em> knows are serialized — a REST resource's parameters and return types, a config
 * mapping, whatever the CDI {@code ObjectMapper} is handed. {@code CanonicalJson} builds its
 * <b>own</b> {@code ObjectMapper} by hand, deliberately and permanently, because the canonical form
 * is a wire contract another service compares byte-for-byte and must not be downstream of any
 * application's customizer. Correct, and this is the price: to the build step scanning for what
 * needs reflecting on, that mapper and everything it touches are invisible. Do not "fix" a
 * recurrence by injecting the CDI mapper.
 *
 * <p>qits-ci paid for this lesson on a deployed binary: every green build's publish died inside
 * {@code CanonicalJson} with Jackson's {@code No serializer found … native image, you may need to
 * configure reflection}, while its JVM suite was green and structurally had to be — on a JVM these
 * types reflect whether anyone registered them or not. This file is that fix applied here before it
 * can happen, which is why {@code EventWireReflectionTest} guards completeness rather than
 * behaviour.
 *
 * <p><b>Why these types.</b> {@link EventFrame} is what arrives on {@code /events/stream} and what
 * the catch-up sweep reads out of the log; {@link EventEnvelope} is the PUT body. The four SCM
 * records are what {@link ScmBackupTriggerListener} subscribes to. That listener reads its one field
 * with {@code readTree} rather than binding, so it would survive without them — but the registration
 * is about the <em>types on the wire</em>, not about this consumer's technique, and the technique is
 * free to change.
 *
 * <p>{@link RepositoryRenamed} is the seventh, and the first one this service <em>publishes</em>.
 * The envelope was already here for the day something did; the payload record is what turns "an
 * absent registration fails at the moment something first does" from a prediction into a line. This
 * is exactly qits-ci's measured failure — every publish dying inside {@code CanonicalJson} with "no
 * serializer found", green JVM suite and all — written down here before it can happen again.
 *
 * <p>{@link ReleaseRequestChanged} is the second <em>published</em> one, and the one the release
 * flow rests on: a request's backing branch is written by qits-githost's merge primitive, which
 * fires no post-receive, so this announcement is the only thing that tells qits-ci a fold exists to
 * build. An unregistered payload here is a release flow that silently never starts.
 *
 * <p>{@link SCMRelease} is the third, and it is the only one on this list this service did not
 * invent: qits-workspaces published it until 2026-09-03, when the release became a tag asked for
 * from here. The <em>wire</em> name is the simple class name, so a replica in another package is the
 * same event to every consumer — which is exactly why it needs a line of its own here, and why the
 * absence would look like the release flow working and nothing downstream ever hearing about it.
 *
 * <p>{@link BuildStatusListener.BuildVerdictPayload} is the one <em>bound</em>
 * consumption: {@code BuildStatusListener} reads qits-ci's {@code BuildSuccessful}/{@code
 * BuildFailed} through {@code CanonicalJson.payloadTo} rather than {@code readTree}, so the record
 * it binds is on the wire path exactly the way the qits-deployments subscriber's payload record is
 * in that service's own registration.
 *
 * <p><b>And why a mix-in by name.</b> {@code CanonicalJson$QitsEventMixin} keeps {@code QitsEvent}'s
 * declared methods — {@code eventId} above all — out of a payload, and Jackson finds its {@code
 * @JsonIgnore}s by calling {@code getDeclaredMethods()} on it, which is reflection like any other.
 * qits-ci measured what leaving it out costs: no crash, no log, {@code eventId} simply present in a
 * payload that is supposed to carry no identity at all — a wire contract violation that breaks
 * nothing visible, which is the worse of the two failure modes. It is a string because the class is
 * private and stays private; {@code EventWireReflectionTest} resolves the string so it cannot rot.
 *
 * <p>All of this is in {@code service/} because {@code service/} is the deployable, and the
 * deployable is what gets built into an image and therefore what tells the builder about itself.
 */
@RegisterForReflection(
    targets = {
      SCMPublishCommit.class,
      SCMPublishTag.class,
      SCMDeleteBranch.class,
      SCMDeleteTag.class,
      RepositoryRenamed.class,
      ReleaseRequestChanged.class,
      SCMRelease.class,
      BuildStatusListener.BuildVerdictPayload.class,
      EventEnvelope.class,
      EventFrame.class
    },
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
