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

  /**
   * By its OWN type, past its {@code @DefaultBean}: the port's injection point is won by the
   * suite's recording fake, so asking for the port here would prove nothing about what ships.
   */
  @Inject Instance<RepositoryRenamedAnnouncer> shippedAnnouncer;

  /** The same, for the second publisher — by its own type, past its {@code @DefaultBean}. */
  @Inject Instance<ReleaseRequestChangedAnnouncer> shippedReleaseAnnouncer;

  /** And the third, which is the release itself. */
  @Inject Instance<SCMReleaseAnnouncer> shippedScmReleaseAnnouncer;

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
            RepositoryRenamed.class,
            ReleaseRequestChanged.class,
            SCMRelease.class,
            BuildStatusListener.BuildVerdictPayload.class,
            DeploymentActiveListener.DeploymentActivePayload.class,
            EventEnvelope.class,
            EventFrame.class),
        Set.of(registration.targets()),
        "the four SCM records and the two bound consumption payloads in, RepositoryRenamed,"
            + " ReleaseRequestChanged and SCMRelease out, the PUT body, the frame — a twelfth"
            + " wire type means a line here");
  }

  /**
   * The publishing half of the same rule. A consumed type is named by a listener's {@code
   * signatures()} and the test below reads it off the bean; a <em>published</em> one has no such
   * declaration to read, so the announcer's own event class is asserted by name — and it is the one
   * whose absence fails inside {@code CanonicalJson}, before an envelope exists, losing the event
   * rather than delaying it.
   */
  @Test
  public void thePublishedEventTypesAreRegistered() {
    Set<Class<?>> targets =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets());
    assertTrue(
        targets.contains(RepositoryRenamed.class),
        "RepositoryRenamedAnnouncer publishes this; an unregistered payload is a lost announcement");
    assertTrue(
        targets.contains(ReleaseRequestChanged.class),
        "ReleaseRequestChangedAnnouncer publishes this, and it is the only thing that tells qits-ci"
            + " a release request's fold exists to build");
    assertTrue(
        targets.contains(SCMRelease.class),
        "SCMReleaseAnnouncer publishes this — the event qits-workspaces used to publish and this"
            + " service does since the release became a tag; the WIRE name is the simple class"
            + " name, so a consumer cannot tell the two apart and must not have to");
  }

  @Test
  public void theReleaseRequestAnnouncerShipsAsABean() {
    assertTrue(!shippedReleaseAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The release announcement, whose absence is the loudest of the three: a release would land, the
   * tag would exist, and no consumer of {@code SCMRelease} — every release pipeline on the platform
   * — would ever hear about it.
   */
  @Test
  public void theScmReleaseAnnouncerShipsAsABean() {
    assertTrue(!shippedScmReleaseAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The replica's signature is the original's, which is the whole of the claim that the publisher
   * moved and the event did not. {@code QitsEvent.signature()} is the simple class name, so the
   * package this record lives in is invisible on the wire — and a rename here would silently mint a
   * second event nobody subscribes to.
   */
  @Test
  public void theReplicatedReleaseEventKeepsTheWireNameAndTheFiveFieldsItAlwaysHad() {
    assertEquals("SCMRelease", SCMRelease.class.getSimpleName());
    assertEquals(
        java.util.List.of(
            "eventId", "projectId", "repository", "repositoryName", "branch", "version",
            "occurredAt"),
        java.util.Arrays.stream(SCMRelease.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList(),
        "field-for-field with qits-workspaces' record, order included — eventId and occurredAt are"
            + " components the canonical mix-in keeps out of the payload, leaving exactly the five"
            + " every existing consumer selects on");
  }

  /**
   * The announcer is a BEAN, which is the whole of how {@code RepositoryService} finds it — an
   * {@code Instance<RepositoryAnnouncer>} that is unsatisfied announces nothing and says nothing
   * about it, which is correct as a configuration and wrong as an accident.
   */
  @Test
  public void theRenameAnnouncerShipsAsABean() {
    assertTrue(!shippedAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The rule that generalises: a durable listener bean is how this service declares it wants an
   * event, and an unregistered one is a binary that subscribes to a signature it cannot deserialize.
   * Written against signatures rather than classes because the durable seam has no {@code
   * eventType()} — a listener takes a frame and reads what it wants — and a signature is an event
   * class's simple name by the same derivation the typed seam used.
   */
  /**
   * Signatures another context publishes and this service binds through a LOCAL payload record —
   * the qits-deployments subscriber shape, where no vocabulary jar carries a type of the wire's
   * name. Each entry maps the signature to the registered record that binds it, so the rule below
   * still refuses a listener whose wire path nothing registered.
   */
  private static final java.util.Map<String, Class<?>> BOUND_BY_LOCAL_RECORD =
      java.util.Map.of(
          "BuildSuccessful", BuildStatusListener.BuildVerdictPayload.class,
          "BuildFailed", BuildStatusListener.BuildVerdictPayload.class,
          "DeploymentActive", DeploymentActiveListener.DeploymentActivePayload.class);

  @Test
  public void everyDurableListenersSignatureNamesARegisteredType() {
    Set<Class<?>> targets =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets());
    Set<String> registered = targets.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    for (QitsDurableEventListener listener : listeners) {
      for (String signature : listener.signatures()) {
        if (QitsRawEventListener.ALL.equals(signature)) {
          continue;
        }
        Class<?> localRecord = BOUND_BY_LOCAL_RECORD.get(signature);
        if (localRecord != null) {
          assertTrue(
              targets.contains(localRecord),
              signature + " is bound by " + localRecord.getName() + ", which is not registered");
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
   * The publish phase's own subscription. Its absence is the quietest failure in the flow: releases
   * would go on happening, deployments would go on going active, and {@code main} would simply never
   * move again — with nothing failing anywhere to say so.
   */
  @Test
  public void theDeploymentActiveListenerIsDiscoverableAsADurableListener() {
    assertTrue(
        listeners.stream().anyMatch(DeploymentActiveListener.class::isInstance),
        "a removed listener is a silent one — and here it is main that stops being finalized");
  }

  /**
   * The publish phase's temporary half has NO listener of its own any more, and that is the fix of
   * 2026-09-04 rather than an omission. It used to consume qits-ci's {@code SoftwareRelease}, an
   * event only a repository carrying a {@code ci-event-release.yml} recipe ever emits, so every
   * recipe-less repository — every SPA — released tags that never reached {@code main}. The
   * deployability fork hangs off this service's OWN release now ({@code
   * ReleaseFinalization.onReleased}, plus its catch-up sweep), which needs no subscription at all.
   */
  @Test
  public void theNonDeployableForkNeedsNoSubscriptionOfItsOwn() {
    assertTrue(
        listeners.stream().noneMatch(l -> l.consumerId().equals("projects-non-deployable-publish")),
        "the SoftwareRelease-driven gate is gone; nothing should subscribe on its behalf");
  }

  @Test
  public void theBuildStatusListenerIsDiscoverableAsADurableListener() {
    assertTrue(
        listeners.stream().anyMatch(BuildStatusListener.class::isInstance),
        "a removed listener is a silent one — and here it is a build gate that never resolves");
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
