package eu.wohlben.qits.projects.containershost;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.Observed;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import eu.wohlben.qits.projects.refinementhost.RefinementContainerFactory;
import eu.wohlben.qits.projects.refinementhost.RefinementRuntime;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link RefinementRuntime} over qits-containers — the refinement sibling of
 * {@link ContainersAgentRuntime}, in the same package because it is the same kind of adapter over
 * the same produced {@link ContainersClient}. The four-answers-never-throws discipline, the patient
 * bring-up and the MISSING/GONE reading all carry over; what differs is the workload word, the
 * teardown verb (refinements are removed, agents only ever stop), and the ref — the refinement row
 * id, minted by this database and already orchestrator-legal, so no name normalization exists here.
 */
@ApplicationScoped
@DefaultBean
public class ContainersRefinementRuntime implements RefinementRuntime {

  private static final Logger LOG = Logger.getLogger(ContainersRefinementRuntime.class);

  /** The workload word — what tells a refinement container from a project agent on the wire. */
  public static final String WORKLOAD = "refinement";

  private static final Duration ENSURE_RETRY_PAUSE = Duration.ofSeconds(5);
  private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);

  @Inject ContainersClient containers;

  @Inject RefinementContainerFactory factory;

  /** The same owner the agent runtime presents — one machine identity per service. */
  @ConfigProperty(name = "qits.projects.containers.owner")
  String owner;

  @ConfigProperty(name = "qits.projects.containers.ensure-patience")
  Duration ensurePatience;

  @Override
  public Optional<ContainerInfo> inspect(long refinementId) {
    ContainersAnswer<Envelope> answer = containers.status(owner, WORKLOAD, ref(refinementId));
    if (answer.succeeded()) {
      Envelope envelope = answer.value();
      return envelope == null ? Optional.empty() : Optional.of(infoOf(envelope));
    }
    if (answer instanceof ContainersAnswer.Refused<Envelope> refused && refused.status() == 404) {
      return Optional.empty();
    }
    throw new InternalServerErrorException(
        "Could not ask qits-containers about the container of refinement "
            + refinementId
            + ": "
            + answer.detail());
  }

  @Override
  public void provision(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    String name = factory.containerName(projectSlug, epicSlug);
    requireNameFree(refinement.id, name);
    ensureVolume(refinement.id);
    bringUp(
        refinement.id,
        name,
        factory.forFreshContainer(refinement, projectSlug, epicSlug, wrapperName));
  }

  @Override
  public void wake(Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    String name = factory.containerName(projectSlug, epicSlug);
    ensureVolume(refinement.id);
    bringUp(
        refinement.id,
        name,
        factory.forExistingContainer(refinement, projectSlug, epicSlug, wrapperName));
  }

  @Override
  public void stop(long refinementId) {
    ContainersAnswer<Envelope> answer =
        containers.stop(owner, WORKLOAD, ref(refinementId), SHORT_TIMEOUT);
    if (!answer.succeeded()) {
      LOG.debugf(
          "Could not stop the container of refinement %s: %s", refinementId, answer.detail());
    }
  }

  @Override
  public void touch(long refinementId) {
    ContainersAnswer<Void> answer =
        containers.touch(owner, WORKLOAD, ref(refinementId), SHORT_TIMEOUT);
    if (!answer.succeeded()) {
      LOG.debugf(
          "Could not stamp the container of refinement %s: %s", refinementId, answer.detail());
    }
  }

  @Override
  public void delete(long refinementId) {
    // Container first, then volume — the wire contract's teardown order. `withVolumes=false`
    // because the volume is its own row and its own delete, never a rider on the container's.
    ContainersAnswer<DeleteOutcome> gone =
        containers.delete(owner, WORKLOAD, ref(refinementId), false, false, SHORT_TIMEOUT);
    if (!gone.succeeded() && !isGone(gone)) {
      throw new InternalServerErrorException(
          "Could not remove the container of refinement "
              + refinementId
              + ": "
              + gone.detail());
    }
    ContainersAnswer<VolumeEnvelope> volume =
        containers.deleteVolume(owner, factory.volumeName(refinementId));
    if (!volume.succeeded() && !isGone(volume)) {
      throw new InternalServerErrorException(
          "Could not remove the volume of refinement " + refinementId + ": " + volume.detail());
    }
  }

  /** A 404 is the state a teardown asks for, not a failure of it. */
  private static boolean isGone(ContainersAnswer<?> answer) {
    return answer instanceof ContainersAnswer.Refused<?> refused && refused.status() == 404;
  }

  private void ensureVolume(long refinementId) {
    String name = factory.volumeName(refinementId);
    ContainersAnswer<VolumeEnvelope> answer = containers.ensureVolume(owner, name);
    if (!answer.succeeded()) {
      LOG.warnf("Could not ensure the refinement volume '%s': %s", name, answer.detail());
    }
  }

  /**
   * Refuse a provision whose container name is already held by another of this owner's places —
   * the same one-arm guard {@link ContainersAgentRuntime#requireNameFree} carries, because a
   * human-derived name proves nothing and can still collide (an epic slug on a project whose slug
   * a deleted project used to hold). Fails open on an unreadable listing.
   */
  private void requireNameFree(Long refinementId, String name) {
    ContainersAnswer<List<Envelope>> answer = containers.list(owner, WORKLOAD);
    if (!answer.succeeded() || answer.value() == null) {
      return;
    }
    boolean taken =
        answer.value().stream().anyMatch(place -> name.equals(place.containerName()));
    // The listing carries no refs, so "is that us" is one status read — our own place holding the
    // name is a re-provision, not a conflict.
    boolean ours =
        inspect(refinementId).map(info -> name.equals(info.containerName())).orElse(false);
    if (taken && !ours) {
      throw new DomainException(
          409,
          "The container name '"
              + name
              + "' is already taken by another refinement. Discard the one that holds it and try"
              + " again.");
    }
  }

  private static String ref(long refinementId) {
    return Long.toString(refinementId);
  }

  /** Only {@code RUNNING} is running — the same honest merge the agent runtime documents. */
  private static ContainerInfo infoOf(Envelope envelope) {
    Observed observed = envelope.state() == null ? null : envelope.state().observed();
    return new ContainerInfo(envelope.containerName(), observed == Observed.RUNNING);
  }

  /** The patient bring-up: {@link ContainersAgentRuntime#holdThrough}'s classifier, verbatim. */
  private void bringUp(Long refinementId, String name, EnsureRequest request) {
    Instant giveUpAt = Instant.now().plus(ensurePatience);
    Duration pause =
        ENSURE_RETRY_PAUSE.compareTo(ensurePatience) > 0 ? ensurePatience : ENSURE_RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      ContainersAnswer<Envelope> answer =
          containers.ensure(owner, WORKLOAD, ref(refinementId), request);
      if (answer.succeeded()) {
        started(refinementId, name, answer.value());
        return;
      }
      if (ContainersAgentRuntime.holdThrough(answer)
          && Instant.now().isBefore(giveUpAt)
          && sleep(pause)) {
        LOG.infof(
            "Attempt %d to bring up the refinement container %s did not land (%s) — asking again,"
                + " holding through the window",
            attempts, name, answer.detail());
        continue;
      }
      throw new InternalServerErrorException(
          "qits-containers could not start the refinement container "
              + name
              + " after "
              + attempts
              + " attempt(s): "
              + answer.detail());
    }
  }

  /** A 2xx whose container is not there is a failed bring-up, not a started one. */
  private static void started(Long refinementId, String name, Envelope envelope) {
    Observed observed =
        envelope == null || envelope.state() == null ? null : envelope.state().observed();
    if (observed == Observed.MISSING || observed == Observed.GONE) {
      String detail = envelope.detail() == null ? "" : envelope.detail();
      throw new InternalServerErrorException(
          "The refinement container "
              + name
              + " of refinement "
              + refinementId
              + " did not start: "
              + detail);
    }
  }

  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
