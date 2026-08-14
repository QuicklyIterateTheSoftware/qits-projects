package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sweep that hands back the credentials of agent containers that are gone — which in this
 * service is where decommissioning happens at all, because nothing here removes a container.
 *
 * <p>Driven through {@code reconcile()} directly rather than through its schedule, the shape {@code
 * AgentIdleSweepTest} uses: the logic is a comparison between two inventories and needs neither a
 * clock nor a scheduler to be worth asserting.
 */
@QuarkusTest
class AgentCredentialReconcileTest {

  @Inject AgentCredentialReconcile reconcile;

  @Inject AgentCommissions commissions;

  @Inject FakeAgentCredentials credentials;

  @Inject FakeContainerRuntime runtime;

  private String live;
  private String orphan;

  @BeforeEach
  void setUp() {
    credentials.reset();
    runtime.reset();
    live = UUID.randomUUID().toString();
    orphan = UUID.randomUUID().toString();
    credentials.enable();
  }

  /** Commissioning off again, so no other class in this suite inherits it. */
  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    credentials.reset();
  }

  /**
   * The whole of it: a commission whose project still has a container is left alone, and one whose
   * project has none is handed back. The second is a container removed out from under this service —
   * or a project deleted and its container cleaned up by hand — and there is no hook that could have
   * caught either.
   */
  @Test
  void anOrphanIsHandedBackAndALiveOneIsSpared() {
    credentials.given("client-live", live);
    credentials.given("client-orphan", orphan);
    runtime.given(live, "live", true);

    reconcile.reconcile();

    assertEquals(Map.of("client-live", live), credentials.live());
    assertTrue(
        credentials.calls().contains("decommission:client-orphan"),
        "the orphan is the one handed back: " + credentials.calls());
  }

  /** A stopped container is still a container, and its credential is still the one it will wake with. */
  @Test
  void aStoppedContainerKeepsItsCredential() {
    credentials.given("client-stopped", live);
    runtime.given(live, "live", false);

    reconcile.reconcile();

    assertEquals(Map.of("client-stopped", live), credentials.live());
  }

  /**
   * The row this database holds goes with the commission, so the next ensure of that project
   * commissions rather than sending a pair idp no longer knows.
   */
  @Test
  void theStoredRowGoesWithTheCommission() {
    commissions.forFreshContainer(orphan); // writes both the idp commission and the row

    reconcile.reconcile();

    assertEquals(Map.of(), credentials.live());
    assertFalse(
        commissions.projectsHoldingACredential().contains(orphan),
        "no pair is left for a wake to send");
  }

  /**
   * A crash between idp's write and this database's leaves a commission no row names. List-and-match
   * is what finds it, which is the whole reason the reconcile reads idp's own listing rather than
   * only its own table.
   */
  @Test
  void aCommissionWithNoRowIsFoundAnyway() {
    credentials.given("client-no-row", orphan);

    reconcile.reconcile();

    assertEquals(Map.of(), credentials.live());
  }

  /**
   * A pass that could not ask reaps nothing. An orchestrator that did not answer is a statement
   * about nothing at all, and reading it as "the container is gone" would revoke the credential of
   * every live agent at once.
   */
  @Test
  void anOrchestratorThatCannotBeAskedReapsNothing() {
    credentials.given("client-live", live);
    runtime.failInspect(new IllegalStateException("qits-containers did not answer"));

    assertEquals(0, reconcile.reconcile());
    assertEquals(Map.of("client-live", live), credentials.live());
  }

  /**
   * The pass runs where the deployment runs it: on a thread with no request context. Every other
   * test here calls {@code reconcile()} from the test method, which the harness has already given a
   * context — which is how a green suite shipped a pass that threw {@code ContextNotActiveException}
   * at its first Panache read, on every boot.
   *
   * <p>What this proves: the body both entry points call reaches the database and hands an orphan
   * back with no context lent to it from outside. The first assertion is what makes the rest mean
   * anything — a thread that arrived holding a context would prove nothing.
   *
   * <p>What it cannot prove: that the schedule and the {@code StartupEvent} reach this body at all.
   * Both entry points gate on {@code LaunchMode.NORMAL} and return in a suite by design, so what is
   * driven here is {@code reconcileQuietly}, which is literally the boot pass's own {@code Runnable}
   * and the whole of what the sweep does past its gate.
   */
  @Test
  void aPassWithNoRequestContextStillHandsAnOrphanBack() throws InterruptedException {
    commissions.forFreshContainer(orphan); // the idp commission and the row, as a fresh container
    AtomicBoolean arrivedWithAContext = new AtomicBoolean(true);

    Thread pass =
        Thread.ofVirtual()
            .name("qits-agent-credential-reconcile-test")
            .start(
                () -> {
                  arrivedWithAContext.set(Arc.container().requestContext().isActive());
                  reconcile.reconcileQuietly();
                });
    pass.join();

    assertFalse(
        arrivedWithAContext.get(),
        "the pass must start with no request context, or this test proves nothing");
    assertEquals(Map.of(), credentials.live());
    assertFalse(
        commissions.projectsHoldingACredential().contains(orphan),
        "the row goes with the commission on a context-less pass too");
  }

  /** With no idp there is nothing to reconcile, and not one call is made. */
  @Test
  void withNoIdpThereIsNothingToReconcile() {
    credentials.reset(); // back to disabled
    credentials.given("client-live", live);

    assertEquals(0, reconcile.reconcile());
    assertEquals(java.util.List.of(), credentials.calls());
  }
}
