package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.persistence.RefinementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Reap refinement-kind idp clients no refinement row claims — the belt for a crash between the
 * idp's write and this database's, and for a discard whose decommission never landed. The
 * refinement twin of {@code agenthost/AgentCredentialReconcile}, filtered to
 * {@link RefinementCredentials#CONTEXT_KIND} so the agent harness's commissions are invisible to it.
 *
 * <p>It only ever removes what the idp's own listing names, and an unreadable listing answers empty
 * — so a pass that cannot ask reaps nothing.
 */
@ApplicationScoped
public class RefinementCommissionReconcile {

  private static final Logger LOG = Logger.getLogger(RefinementCommissionReconcile.class);

  @Inject RefinementCredentials credentials;

  @Inject RefinementRepository refinements;

  void onStart(@Observes StartupEvent event) {
    reconcile();
  }

  @Scheduled(
      every = "{qits.projects.refinement-credentials.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    reconcile();
  }

  void reconcile() {
    if (!credentials.enabled()) {
      return;
    }
    List<RefinementCredentials.Commission> commissions = credentials.listRefinementCommissions();
    if (commissions.isEmpty()) {
      return;
    }
    Set<String> claimed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    refinements.holdingACredential().stream()
                        .map(row -> row.commissionedClientId)
                        .collect(Collectors.toSet()));
    for (RefinementCredentials.Commission commission : commissions) {
      if (claimed.contains(commission.clientId())) {
        continue;
      }
      LOG.infof(
          "Decommissioning the orphaned refinement client %s (refinement %s): no row claims it",
          commission.clientId(), commission.refinementId());
      credentials.decommission(commission.clientId());
    }
  }
}
