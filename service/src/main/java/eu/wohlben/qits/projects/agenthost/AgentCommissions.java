package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.entity.AgentCredential;
import eu.wohlben.qits.projects.persistence.AgentCredentialRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which credential a project's agent container is started with — the commission side of the
 * container lifecycle, and the one place the two ensure arms differ about it.
 *
 * <h2>The credential mirrors the container, not the request</h2>
 *
 * <p><b>A fresh container gets a fresh credential; a container brought back keeps the one it has.</b>
 * {@link #forFreshContainer} commissions and {@link #forExistingContainer} only reads, because the
 * environment a container was created with is the environment it comes back with — qits-containers
 * starts the container the place already names rather than injecting anything again.
 *
 * <p>That asymmetry is why {@link #forExistingContainer} reads a stored pair instead of minting one.
 * It is not a cache: qits-containers hashes a workload's whole spec, environment included, to decide
 * whether an {@code ensure} may start the container in place. A wake that sent a different secret
 * would be a spec change and would <em>replace</em> the container every time — the exact defect
 * {@code AgentContainerFactory.forRestart} records, and the reason nothing per-call may enter that
 * spec. The row is what makes the wake arm's request identical to the fresh arm's.
 *
 * <h2>Decommissioning is reconcile-driven here, and that is a property of this repository</h2>
 *
 * <p>The model says a credential ends when its container does. <b>Nothing in this service removes an
 * agent container.</b> The stop verb and the idle sweep both stop and never remove; deleting a
 * project leaves its container standing (which is what {@code requireNameFree} 409s on later); and
 * {@code ContainerRuntime} carries no removal verb at all. So there is no call site to decommission
 * from, and the two real paths are the ones below: this class hands back a project's previous
 * credential when it provisions a <em>replacement</em> container, and
 * {@link AgentCredentialReconcile} hands back everything whose container is gone. If a removal verb
 * is ever added, it decommissions there too and the reconcile becomes the belt it should be.
 *
 * <h2>Failing loudly</h2>
 *
 * <p>A commission holds through the answers that are about the moment — nothing answering, a 401 or
 * 403 across an idp cutover, a 5xx — for {@code qits.projects.agent-credentials.commission-patience},
 * the same shape and the same reasoning as {@code ContainersAgentRuntime.holdThrough}. Past that it
 * throws, and the ensure ladder answers {@code FAILED} with the reason on {@code failureDetail}. A
 * container started without the credential it should have holds none, and every read it makes is
 * refused a long way from here with nothing pointing back at this moment.
 */
@ApplicationScoped
public class AgentCommissions {

  private static final Logger LOG = Logger.getLogger(AgentCommissions.class);

  /** How long a commission waits between two attempts. */
  private static final Duration RETRY_PAUSE = Duration.ofSeconds(3);

  @Inject AgentCredentials credentials;

  @Inject AgentCredentialRepository store;

  /**
   * How long a commission holds through an idp that cannot answer or cannot authorize yet. Thirty
   * seconds: the measured window behind {@code qits.projects.containers.ensure-patience} is an idp
   * cutover, and this call is made against that same idp — but it sits in front of an image pull
   * somebody is waiting on, so it is the shorter of the two on purpose.
   */
  @ConfigProperty(name = "qits.projects.agent-credentials.commission-patience")
  Duration commissionPatience;

  /**
   * The credential a <b>fresh</b> container is started with, or empty when this deployment
   * commissions nothing.
   *
   * <p>Any credential the project still holds is handed back first. A fresh container is a fresh
   * context, so the old pair belongs to a container that is gone or is being replaced, and leaving
   * it live would leak a credential nothing can present.
   *
   * @throws AgentCredentialException when no credential could be commissioned inside the window
   */
  public Optional<AgentCredentials.Commissioned> forFreshContainer(String projectId) {
    if (!credentials.enabled()) {
      return Optional.empty();
    }
    handBack(projectId);
    AgentCredentials.Commissioned commissioned = commissionPatiently(projectId);
    QuarkusTransaction.requiringNew()
        .run(() -> store.put(projectId, commissioned.clientId(), commissioned.secret()));
    LOG.infof(
        "Commissioned the client %s for the agent container of project %s",
        commissioned.clientId(), projectId);
    return Optional.of(commissioned);
  }

  /**
   * The credential this project's container already holds, or empty — for the wake arm, which
   * commissions nothing.
   *
   * <p>Empty for two reasons that are the same reason here: this deployment commissions nothing, or
   * the container predates commissioning. Both send the spec that container was created with, which
   * is what keeps a wake a start in place.
   */
  public Optional<AgentCredentials.Commissioned> forExistingContainer(String projectId) {
    if (!credentials.enabled()) {
      return Optional.empty();
    }
    return store
        .findByProject(projectId)
        .map(row -> new AgentCredentials.Commissioned(row.clientId, row.clientSecret));
  }

  /**
   * Hand this project's credential back and forget the row. Idempotent, and best-effort at the idp:
   * a client id it no longer holds is the state this asks for.
   */
  public void handBack(String projectId) {
    Optional<AgentCredential> held = store.findByProject(projectId);
    held.ifPresent(row -> credentials.decommission(row.clientId));
    if (held.isPresent()) {
      QuarkusTransaction.requiringNew().run(() -> store.forget(projectId));
      LOG.infof(
          "Decommissioned the client %s of project %s", held.get().clientId, projectId);
    }
  }

  /**
   * Hand back one commission the reconcile found, whether or not a row here names it — a crash
   * between the idp write and this database's is exactly the case list-and-match exists for.
   */
  void handBack(AgentCredentials.Commission commission) {
    credentials.decommission(commission.clientId());
    QuarkusTransaction.requiringNew().run(() -> store.forget(commission.projectId()));
    LOG.infof(
        "Decommissioned the orphaned client %s of project %s: it has no agent container",
        commission.clientId(), commission.projectId());
  }

  /** Every project this service holds a stored credential for. */
  List<String> projectsHoldingACredential() {
    return store.projectsHoldingACredential();
  }

  /** One commission, asked for again while the answers are about the moment. */
  private AgentCredentials.Commissioned commissionPatiently(String projectId) {
    Instant giveUpAt = Instant.now().plus(commissionPatience);
    // Never pause past the window: a pause longer than the patience would make a short window mean
    // one attempt while looking like a window.
    Duration pause =
        RETRY_PAUSE.compareTo(commissionPatience) > 0 ? commissionPatience : RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        return credentials.commission(projectId);
      } catch (AgentCredentialException e) {
        if (!e.retryable() || !Instant.now().isBefore(giveUpAt) || !sleep(pause)) {
          throw new AgentCredentialException(
              "Could not commission a credential for the agent container of project "
                  + projectId
                  + " after "
                  + attempts
                  + " attempt(s): "
                  + e.getMessage(),
              e.retryable(),
              e);
        }
        LOG.infof(
            "Attempt %d to commission a credential for project %s did not land (%s) — asking again,"
                + " holding through the window",
            attempts, projectId, e.getMessage());
      }
    }
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
