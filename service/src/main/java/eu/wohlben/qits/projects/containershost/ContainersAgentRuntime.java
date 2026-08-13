package eu.wohlben.qits.projects.containershost;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.Observed;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.projects.agenthost.AgentContainerFactory;
import eu.wohlben.qits.projects.agenthost.ContainerRuntime;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.InternalServerErrorException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link ContainerRuntime} over qits-containers — the whole of this service's container vocabulary,
 * and it is HTTP.
 *
 * <p><b>This process holds no docker socket and spawns no process.</b> Every line of docker
 * vocabulary that used to live in {@code DockerAgentRuntime} — {@code run}, {@code start},
 * {@code stop}, {@code ps}, {@code volume create}, {@code network inspect}/{@code create} — is one
 * call to the orchestrator, which owns the daemon. The network went further than that: it is the
 * bootstrap's to create, so this class does not ensure one and has no startup observer at all.
 *
 * <p><b>The client never throws, and its four answers are what every decision here is made on.</b> A
 * refusal and an unreachable service mean opposite things — one is evidence about the request, the
 * other about nothing at all — so a method that collapsed them would report "there is no container"
 * for "nobody answered", and the ladder would provision a second one. Do not add a fifth outcome by
 * catching something.
 *
 * <p><b>{@code @DefaultBean}.</b> It yields to any other bean of the type, which is what lets the
 * suite install {@code FakeContainerRuntime} and reach no orchestrator — the same arrangement
 * {@code ConfiguredGitHostAddress} has, and the same one {@code DockerAgentRuntime} had. Keep the
 * annotation: dropping it makes the two an ambiguous dependency and the build fails at
 * {@code ArcProcessor#validate}, for every test at once.
 */
@ApplicationScoped
@DefaultBean
public class ContainersAgentRuntime implements ContainerRuntime {

  private static final Logger LOG = Logger.getLogger(ContainersAgentRuntime.class);

  /**
   * The workload every place this class addresses belongs to. One word, this consumer's own: the
   * registry's identity is {@code owner/workload/ref}, so this is what tells a project agent from
   * anything else qits-projects might one day ask the orchestrator for, and it is what scopes
   * {@link #listAgentContainers}.
   */
  public static final String WORKLOAD = "project-agent";

  /** How long a bring-up waits between two attempts at the same place. See {@link #holdThrough}. */
  private static final Duration ENSURE_RETRY_PAUSE = Duration.ofSeconds(5);

  /** How long a stop or a touch may take. Both are one docker call behind one registry write. */
  private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);

  @Inject ContainersClient containers;

  @Inject AgentContainerFactory factory;

  /**
   * Who this process <b>is</b> to the orchestrator, and the second half of every place it addresses.
   *
   * <p>It must equal the {@code sub} of the machine token this service presents once the gate is on,
   * because {@code OwnerGuard} compares them — so the shipped default reads
   * {@code quarkus.oidc-client.client-id} and the coupling lives in one place, the key's own comment
   * in {@code application.properties}. It is also the scope: two environments sharing one docker
   * daemon are {@code dev-qits-projects} and {@code prod-qits-projects}, and neither one's rows name
   * the other's containers.
   */
  @ConfigProperty(name = "qits.projects.containers.owner")
  String owner;

  /**
   * How long a bring-up holds through an orchestrator that cannot authorize it yet, or cannot be
   * reached at all. The measured window is the trailing edge of a qits-platform-idp cutover — see
   * {@link #holdThrough}.
   */
  @ConfigProperty(name = "qits.projects.containers.ensure-patience")
  Duration ensurePatience;

  @Override
  public String containerName(String projectSlug) {
    return factory.containerName(projectSlug);
  }

  @Override
  public Optional<ContainerInfo> inspect(String projectId) {
    ContainersAnswer<Envelope> answer = containers.status(owner, WORKLOAD, projectId);
    if (answer.succeeded()) {
      Envelope envelope = answer.value();
      return envelope == null ? Optional.empty() : Optional.of(infoOf(envelope));
    }
    if (answer instanceof ContainersAnswer.Refused<Envelope> refused && refused.status() == 404) {
      // The orchestrator holds no row for this place, which is the one thing "there is no container"
      // can mean here.
      return Optional.empty();
    }
    throw new InternalServerErrorException(
        "Could not ask qits-containers about the agent container of project "
            + projectId
            + ": "
            + answer.detail());
  }

  @Override
  public String run(String projectId, String projectSlug, String repoName) {
    String name = factory.containerName(projectSlug);
    requireNameFree(projectId, name);
    return bringUp(projectId, name, factory.forProject(projectId, projectSlug, repoName));
  }

  @Override
  public String restart(String projectId, String projectSlug, String repoName) {
    String name = factory.containerName(projectSlug);
    return bringUp(projectId, name, factory.forRecreation(projectId, projectSlug, repoName));
  }

  @Override
  public void stop(String projectId) {
    ContainersAnswer<Envelope> answer = containers.stop(owner, WORKLOAD, projectId, SHORT_TIMEOUT);
    if (!answer.succeeded()) {
      // Best-effort by contract: the caller is either the idle sweep, which will come round again,
      // or the Stop verb, whose own answer is what the panel reads.
      LOG.debugf(
          "Could not stop the agent container of project %s: %s", projectId, answer.detail());
    }
  }

  @Override
  public void touch(String projectId) {
    ContainersAnswer<Void> answer = containers.touch(owner, WORKLOAD, projectId, SHORT_TIMEOUT);
    if (!answer.succeeded()) {
      LOG.debugf(
          "Could not stamp the agent container of project %s as active: %s",
          projectId, answer.detail());
    }
  }

  @Override
  public List<ContainerInfo> listAgentContainers() {
    ContainersAnswer<List<Envelope>> answer = containers.list(owner, WORKLOAD);
    if (!answer.succeeded()) {
      // An empty listing is a statement about no particular container, so the sweep does nothing
      // this pass rather than acting on an answer nobody gave.
      LOG.warnf("Could not list this owner's project-agent containers: %s", answer.detail());
      return List.of();
    }
    List<ContainerInfo> infos = new ArrayList<>();
    for (Envelope envelope : answer.value() == null ? List.<Envelope>of() : answer.value()) {
      infos.add(infoOf(envelope));
    }
    return infos;
  }

  @Override
  public String projectVolumeName(String projectId) {
    return factory.projectVolumeName(projectId);
  }

  @Override
  public void ensureProjectVolume(String projectId) {
    String name = factory.projectVolumeName(projectId);
    ContainersAnswer<VolumeEnvelope> answer = containers.ensureVolume(owner, name);
    if (!answer.succeeded()) {
      LOG.warnf("Could not ensure the project volume '%s': %s", name, answer.detail());
    }
  }

  /**
   * One place, as this service reads it.
   *
   * <p><b>Only {@code RUNNING} is running.</b> {@code PENDING} and {@code STARTING} are a bring-up
   * somebody else is in the middle of, or one that died between two of the orchestrator's own
   * writes; {@code EXITED} is a stop; {@code MISSING} and {@code GONE} are a container that is not
   * there. The ladder's answer to every one of them is the same — recreate the place — so merging
   * them here is the honest reading rather than a lossy one, and it is what un-wedges a row left
   * mid-bring-up by a crash.
   */
  private static ContainerInfo infoOf(Envelope envelope) {
    Observed observed = envelope.state() == null ? null : envelope.state().observed();
    return new ContainerInfo(envelope.containerName(), observed == Observed.RUNNING);
  }

  /**
   * Refuse a provision whose container name is already held by <b>another</b> of this owner's
   * places.
   *
   * <p>This is what the {@code qits.project} label used to prove, expressed against rows instead.
   * The name is derived from the project slug, which is unique among <em>live</em> projects only, so
   * deleting a project — which does not remove its agent container — leaves the name sitting there
   * for the next project that takes the freed slug. The registry's {@code container_name} is unique
   * across every row it holds, so an ensure would be refused anyway; it would be refused as a 500
   * from a constraint, which says nothing an operator can act on. Asked here, the refusal names both
   * projects and the way out.
   *
   * <p>It costs one call and only on the provisioning arm, because that is the only arm that can
   * take a name it does not already hold. A listing this owner cannot read is not a conflict: it
   * fails open, and the ensure behind it is what then reports whatever really went wrong.
   */
  private void requireNameFree(String projectId, String name) {
    ContainersAnswer<List<Envelope>> answer = containers.list(owner, WORKLOAD);
    if (!answer.succeeded() || answer.value() == null) {
      LOG.debugf(
          "Could not check whether the container name '%s' is free: %s", name, answer.detail());
      return;
    }
    boolean taken = answer.value().stream().anyMatch(place -> name.equals(place.containerName()));
    if (taken) {
      throw new DomainException(
          409,
          "The container name '"
              + name
              + "' is already taken by another project's agent container. Slugs are unique among"
              + " live projects, so this is a container left behind by a deleted one; remove it"
              + " and try again.");
    }
  }

  /**
   * Ask the orchestrator to put this project's container at its place, and answer the name it is at.
   *
   * <p><b>One attempt per answer about the request, and a patient loop for the two answers that are
   * about nothing but the moment</b> — qits-ci's {@code CiDaemonLauncher} rule, and the same
   * classifier: {@link #holdThrough}. Retrying is safe because {@code ensure} is a PUT per
   * {@code (owner, workload, ref)}, so every attempt addresses the same place and a container an
   * unanswered attempt created is adopted rather than duplicated.
   *
   * <p><b>A 2xx whose container is not there is a failed bring-up.</b> The wire contract is explicit
   * that an ensure whose container did not start is a true answer rather than a failed request — the
   * row exists, it says {@code MISSING}, and it carries what docker said — so the status alone does
   * not answer this method's question. Reading it as started would open a refinement panel onto a
   * container that never existed.
   */
  private String bringUp(String projectId, String name, EnsureRequest request) {
    Instant giveUpAt = Instant.now().plus(ensurePatience);
    // Never pause past the window itself: a pause longer than the patience would make a short
    // patience mean one attempt while looking like a window, which is the shape a test cannot see.
    Duration pause =
        ENSURE_RETRY_PAUSE.compareTo(ensurePatience) > 0 ? ensurePatience : ENSURE_RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      ContainersAnswer<Envelope> answer = containers.ensure(owner, WORKLOAD, projectId, request);
      if (answer.succeeded()) {
        return started(projectId, name, answer.value());
      }
      if (holdThrough(answer) && Instant.now().isBefore(giveUpAt) && sleep(pause)) {
        LOG.infof(
            "Attempt %d to bring up the agent container %s did not land (%s) — asking again,"
                + " holding through the window",
            attempts, name, answer.detail());
        continue;
      }
      throw new InternalServerErrorException(
          "qits-containers could not start the agent container "
              + name
              + " after "
              + attempts
              + " attempt(s): "
              + answer.detail());
    }
  }

  /** A 2xx, read for whether a container is actually there — see {@link #bringUp}. */
  private static String started(String projectId, String name, Envelope envelope) {
    Observed observed =
        envelope == null || envelope.state() == null ? null : envelope.state().observed();
    if (observed == Observed.MISSING || observed == Observed.GONE) {
      String detail = envelope.detail() == null ? "" : envelope.detail();
      throw new InternalServerErrorException(
          "The agent container " + name + " of project " + projectId + " did not start: " + detail);
    }
    return envelope == null || envelope.containerName() == null || envelope.containerName().isBlank()
        ? name
        : envelope.containerName();
  }

  /**
   * The two answers another attempt could change, and the one place that decision is made. Copied
   * from qits-ci's {@code CiDaemonLauncher.holdThrough}, whose javadoc carries the measurement.
   *
   * <p><b>401 and 403 are in it, and that is the 2026-08-12 lesson.</b> They read like statements
   * about the request — the owner guard said no — and for a stable deployment they are. Across an
   * idp cutover they are a statement about the moment instead: the same call with the same owner
   * succeeds a minute later, because the token or the key that validates it has been replaced. There
   * is no way to tell the two apart from here, so the patient reading is the safe one — every call
   * this predicate governs is idempotent, so a retry that was never needed costs one request.
   *
   * <p>Everything else is an answer about the request and is taken at its word: {@code
   * SPEC_CONFLICT}, {@code IMAGE_MISSING}, a 400 on a value, a 404 saying the place is already gone.
   */
  static boolean holdThrough(ContainersAnswer<?> answer) {
    if (answer.unreachable()) {
      return true;
    }
    return answer instanceof ContainersAnswer.Refused<?> refused
        && (refused.status() == 401 || refused.status() == 403);
  }

  /** Wait, or report that this thread is being asked to stop — in which case the wait is over. */
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
