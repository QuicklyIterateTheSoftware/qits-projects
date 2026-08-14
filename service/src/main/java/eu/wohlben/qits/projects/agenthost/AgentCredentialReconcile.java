package eu.wohlben.qits.projects.agenthost;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Hands back the credentials of agent containers that are gone.
 *
 * <p><b>This is where decommissioning actually happens, and that is a fact about this service rather
 * than a fallback.</b> A credential's lifetime is its container's, and nothing here removes a
 * container: the stop verb and the idle sweep both stop and never remove, deleting a project leaves
 * its container standing, and {@link ContainerRuntime} carries no removal verb at all. So the
 * lifecycle hook the model asks for has no call site — {@code AgentCommissions.forFreshContainer}
 * covers the one case this service can see (a project provisioning a replacement container), and
 * everything else is found here, by comparing what idp says this service commissioned against what
 * the orchestrator says exists.
 *
 * <p>It also closes the crash window that no hook could: a process that died between idp's write and
 * this database's leaves a commission no row names, and list-and-match finds it anyway.
 *
 * <h2>Why it asks per project instead of reading the listing</h2>
 *
 * <p>{@link ContainerRuntime#listAgentContainers} answers an empty list both for "this owner has no
 * containers" and for "the orchestrator could not be asked" — a distinction its callers do not need
 * and this one cannot do without, because reaping on the second would revoke the credential of every
 * live agent at once. {@link ContainerRuntime#inspect} draws exactly the line that is needed: empty
 * is a place the orchestrator holds no row for, and anything else throws. It is also the only
 * question that can be asked at all here, since the listing's envelopes carry no ref and a
 * <em>deleted</em> project's container name cannot be derived from anything left.
 *
 * <p>One call per commissioned project, hourly. There are as many commissions as there are agent
 * containers, which is a handful.
 *
 * <p><b>A pass that cannot ask reaps nothing.</b> The first orchestrator failure ends the pass with a
 * warning; the next one comes round in an hour, and a credential that outlives its container by an
 * hour costs nothing the model has not already accepted for the token grace.
 */
@ApplicationScoped
public class AgentCredentialReconcile {

  private static final Logger LOG = Logger.getLogger(AgentCredentialReconcile.class);

  @Inject AgentCredentials credentials;

  @Inject AgentCommissions commissions;

  @Inject ContainerRuntime runtime;

  /**
   * At boot, because a process that crashed holding commissions is exactly the case this exists for
   * and an hour is a long time to leave them. On a virtual thread and off the startup path, the
   * {@code StartupSelfSeed} precedent: it reaches two services over the network and readiness must
   * not wait on either.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-agent-credential-reconcile").start(this::reconcileQuietly);
  }

  /**
   * Hourly thereafter. Packaged runs only, the gate {@code ScheduledBackupSweep} and {@code
   * StartupSelfSeed} both carry: a suite or a {@code quarkus:dev} session must not start revoking
   * credentials in the background.
   */
  @Scheduled(
      every = "${qits.projects.agent-credentials.reconcile-interval:1h}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweep() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    reconcileQuietly();
  }

  /**
   * The body both entry points run, with every failure logged and none rethrown.
   *
   * <p>Package-private so the suite can drive it on a thread of its own — which is the only way to
   * reproduce what the deployment does, since both entry points return early in test launch mode.
   */
  void reconcileQuietly() {
    try {
      reconcile();
    } catch (RuntimeException e) {
      LOG.error("The agent-credential reconcile failed — retried on the next interval.", e);
    }
  }

  /**
   * One pass; answers how many credentials it handed back.
   *
   * <p>Package-private and driven directly by the suite, the same shape {@code AgentIdleSweep.sweep}
   * has: the logic is a comparison between two inventories and needs neither a clock nor a schedule
   * to be worth asserting.
   *
   * <p>{@link ActivateRequestContext} because the reads below reach Panache and neither entry point
   * carries a context of its own — the boot pass is a bare virtual thread, and a scheduler thread is
   * nobody's request. Without it the pass threw {@code ContextNotActiveException} at the first read,
   * which is the {@code SelfSeedService} and {@code RepositoryService.backupToTwin} case exactly.
   * It sits here rather than on {@link #reconcileQuietly()} so a direct call cannot get it wrong
   * either; the writes still open their own transactions in {@link AgentCommissions}.
   *
   * <p>It failed closed — the throw came before any revoke — so nothing was ever wrongly handed
   * back. Nothing was reaped either, and the swallowed error is why it went on for releases.
   */
  @ActivateRequestContext
  int reconcile() {
    if (!credentials.enabled()) {
      return 0;
    }
    List<AgentCredentials.Commission> live = credentials.listAgentContainerCommissions();
    // Both inventories, because each holds something the other cannot: idp holds commissions this
    // database never recorded (a crash between the two writes), and this database holds rows for
    // commissions idp may have lost.
    Set<String> projects = new LinkedHashSet<>();
    live.forEach(commission -> projects.add(commission.projectId()));
    projects.addAll(commissions.projectsHoldingACredential());
    if (projects.isEmpty()) {
      return 0;
    }

    Set<String> gone = new LinkedHashSet<>();
    for (String projectId : projects) {
      try {
        if (runtime.inspect(projectId).isEmpty()) {
          gone.add(projectId);
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not ask qits-containers about the agent container of project %s — reconciling"
                + " nothing this pass: %s",
            projectId, e.getMessage());
        return 0;
      }
    }

    int handedBack = 0;
    for (AgentCredentials.Commission commission : live) {
      if (gone.contains(commission.projectId())) {
        commissions.handBack(commission);
        handedBack++;
      }
    }
    // Whatever is left is a row here naming a commission idp did not list — already gone on that
    // side, so this is the row catching up.
    for (String projectId : gone) {
      if (live.stream().noneMatch(commission -> commission.projectId().equals(projectId))) {
        commissions.handBack(projectId);
        handedBack++;
      }
    }
    return handedBack;
  }
}
