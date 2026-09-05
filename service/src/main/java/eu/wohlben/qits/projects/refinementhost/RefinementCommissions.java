package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.agenthost.AgentCredentialException;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which credential a refinement's container is started with — the refinement twin of
 * {@code agenthost/AgentCommissions}, storing the pair <b>on the refinement row</b> rather than in
 * a sidecar table, because the row and the credential share a lifetime exactly.
 *
 * <p>The asymmetry is the same one: a fresh container gets a fresh credential, a container brought
 * back keeps the one it has — qits-containers hashes the whole spec, environment included, so the
 * wake arm must reproduce the stored pair byte for byte or every resume replaces the container.
 *
 * <p>Unlike the agent harness, refinement <em>has</em> teardown call sites, so decommissioning is
 * explicit at the seams — a failed provision, the discard, and the fresh arm replacing a prior pair
 * — with {@link RefinementCommissionReconcile} as the belt for a crash between the idp's write and
 * this database's.
 */
@ApplicationScoped
public class RefinementCommissions {

  private static final Logger LOG = Logger.getLogger(RefinementCommissions.class);

  private static final Duration RETRY_PAUSE = Duration.ofSeconds(3);

  @Inject RefinementCredentials credentials;

  @Inject RefinementRepository refinements;

  /** The same window the agent harness holds a commission through, for the same idp. */
  @ConfigProperty(name = "qits.projects.agent-credentials.commission-patience")
  Duration commissionPatience;

  /**
   * The credential a fresh container is started with, or empty when this deployment commissions
   * nothing. Any pair the row still holds is handed back first, and the fresh pair is written onto
   * the row before the spec is built so the wake arm can read it back.
   */
  public Optional<RefinementCredentials.Commissioned> forFreshContainer(Refinement refinement) {
    if (!credentials.enabled()) {
      return Optional.empty();
    }
    handBack(refinement);
    RefinementCredentials.Commissioned commissioned =
        commissionPatiently(refinement.id, refinement.projectId);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                refinements
                    .findByIdOptional(refinement.id)
                    .ifPresent(
                        row -> {
                          row.commissionedClientId = commissioned.clientId();
                          row.commissionedClientSecret = commissioned.secret();
                        }));
    refinement.commissionedClientId = commissioned.clientId();
    refinement.commissionedClientSecret = commissioned.secret();
    LOG.infof(
        "Commissioned the client %s for refinement %s", commissioned.clientId(), refinement.id);
    return Optional.of(commissioned);
  }

  /** The pair the row holds, or empty — the wake arm, which commissions nothing. */
  public Optional<RefinementCredentials.Commissioned> forExistingContainer(Refinement refinement) {
    if (!credentials.enabled()) {
      return Optional.empty();
    }
    if (refinement.commissionedClientId == null || refinement.commissionedClientSecret == null) {
      return Optional.empty();
    }
    return Optional.of(
        new RefinementCredentials.Commissioned(
            refinement.commissionedClientId, refinement.commissionedClientSecret));
  }

  /** Hand the row's credential back and clear the columns. Idempotent, best-effort at the idp. */
  public void handBack(Refinement refinement) {
    String held = refinement.commissionedClientId;
    if (held == null) {
      return;
    }
    credentials.decommission(held);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                refinements
                    .findByIdOptional(refinement.id)
                    .ifPresent(
                        row -> {
                          row.commissionedClientId = null;
                          row.commissionedClientSecret = null;
                        }));
    refinement.commissionedClientId = null;
    refinement.commissionedClientSecret = null;
    LOG.infof("Decommissioned the client %s of refinement %s", held, refinement.id);
  }

  private RefinementCredentials.Commissioned commissionPatiently(
      Long refinementId, String projectId) {
    Instant giveUpAt = Instant.now().plus(commissionPatience);
    Duration pause =
        RETRY_PAUSE.compareTo(commissionPatience) > 0 ? commissionPatience : RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        return credentials.commission(refinementId, projectId);
      } catch (AgentCredentialException e) {
        if (!e.retryable() || !Instant.now().isBefore(giveUpAt) || !sleep(pause)) {
          throw new AgentCredentialException(
              "Could not commission a credential for refinement "
                  + refinementId
                  + " after "
                  + attempts
                  + " attempt(s): "
                  + e.getMessage(),
              e.retryable(),
              e);
        }
        LOG.infof(
            "Attempt %d to commission a credential for refinement %s did not land (%s) — asking"
                + " again, holding through the window",
            attempts, refinementId, e.getMessage());
      }
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
