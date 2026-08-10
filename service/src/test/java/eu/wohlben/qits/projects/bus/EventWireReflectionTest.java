package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection} — which is to say, guards the <em>completeness</em> of the
 * registration, because its correctness is not something this suite can reach.
 *
 * <p><b>Say plainly what a JVM test can and cannot prove here.</b> On a JVM every class reflects
 * whether anyone registered it or not, so nothing below would fail if the annotation were deleted
 * tomorrow, except the assertions that read the annotation itself. Only the <b>native artifact</b>,
 * running against a real qits-events, proves that the registration does its job — qits-ci measured
 * the failure it prevents twice, once as a thrown "no serializer found" and once as an {@code
 * eventId} silently appearing in a payload that must carry no identity. What is checkable is written
 * here: that the registered set still covers every type the wire path touches, and that the one
 * entry named as a string still resolves.
 */
@QuarkusTest
public class EventWireReflectionTest {

  /** The private nested mix-in {@link EventWireReflection} can only name as a string. */
  private static final String MIXIN =
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  @Inject @Any Instance<QitsDurableEventListener> listeners;

  @Test
  public void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            SCMPublishCommit.class,
            SCMPublishTag.class,
            SCMDeleteBranch.class,
            SCMDeleteTag.class,
            EventEnvelope.class,
            EventFrame.class),
        Set.of(registration.targets()),
        "the four SCM records in, the PUT body out, the frame — a seventh wire type means a line"
            + " here");
  }

  /**
   * The rule that generalises: a durable listener bean is how this service declares it wants an
   * event, and an unregistered one is a binary that subscribes to a signature it cannot deserialize.
   * Written against signatures rather than classes because the durable seam has no {@code
   * eventType()} — a listener takes a frame and reads what it wants — and a signature is an event
   * class's simple name by the same derivation the typed seam used.
   */
  @Test
  public void everyDurableListenersSignatureNamesARegisteredType() {
    Set<String> registered =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets())
            .stream()
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    for (QitsDurableEventListener listener : listeners) {
      for (String signature : listener.signatures()) {
        if (QitsRawEventListener.ALL.equals(signature)) {
          continue;
        }
        assertTrue(
            registered.contains(signature),
            listener.getClass().getName()
                + " listens for "
                + signature
                + ", which no registered type is named after");
      }
    }
  }

  /**
   * The listener is a BEAN, which is the whole of how it is registered — {@code EventDispatcher}
   * injects {@code Instance<QitsDurableEventListener>}, so being injectable is being subscribed. A
   * listener ArC removed would subscribe to nothing, be swept for nothing, and say nothing about it.
   */
  @Test
  public void theBackupTriggerListenerIsDiscoverableAsADurableListener() {
    assertTrue(
        listeners.stream().anyMatch(ScmBackupTriggerListener.class::isInstance),
        "a removed listener is a silent one");
  }

  /**
   * The string entry, kept honest. A rename or a move of the mix-in would otherwise leave a
   * registration naming nothing, and the consequence is not a crash but {@code eventId} appearing in
   * a canonical payload.
   */
  @Test
  public void theMixinNamedByStringStillExistsAndStillHidesTheEnvelopesFields() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertEquals(
        MIXIN,
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames())
            .iterator()
            .next());
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(
        eventId.getAnnotation(JsonIgnore.class),
        "the mix-in is registered because this @JsonIgnore is read by reflection");
  }
}
