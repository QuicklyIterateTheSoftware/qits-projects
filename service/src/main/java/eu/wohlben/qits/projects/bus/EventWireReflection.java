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
 * the catch-up sweep reads out of the log; {@link EventEnvelope} is the PUT body, and it is here even
 * though this service publishes nothing today, because an absent registration fails at the moment
 * something first does. The four SCM records are what {@link ScmBackupTriggerListener} subscribes
 * to. That listener reads its one field with {@code readTree} rather than binding, so it would
 * survive without them — but the registration is about the <em>types on the wire</em>, not about
 * this consumer's technique, and the technique is free to change.
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
      EventEnvelope.class,
      EventFrame.class
    },
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
