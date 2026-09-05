package eu.wohlben.qits.projects.refinementhost;

import java.util.List;

/**
 * The commission API of qits-idp as the refinement harness needs it: a credential per refinement
 * container, given back when the refinement ends. The refinement twin of
 * {@code agenthost/AgentCredentials} — a second seam rather than a widened one, because the two
 * harnesses' context kinds, storage rows and reconciles are disjoint and must stay independently
 * fake-able.
 *
 * <p>The sole implementation is {@code idphost/IdpRefinementCredentials}, {@code @DefaultBean} so
 * the suite installs a double and reaches no idp. <b>Absent is the shipped configuration</b>: with
 * {@code quarkus.oidc-client.client-enabled=false} nothing is commissioned and a container's spec
 * is byte for byte the spec it would be without any of this.
 */
public interface RefinementCredentials {

  /** The context kind every commission this harness makes carries. Storage, on idp's rows. */
  String CONTEXT_KIND = "refinement";

  /** A freshly commissioned credential. The secret is answered once and never again. */
  record Commissioned(String clientId, String secret) {}

  /** One live commission, as the reconcile reads it back. No secret, ever. */
  record Commission(String clientId, String refinementId) {}

  /** Whether this deployment commissions at all. Read before anything else. */
  boolean enabled();

  /**
   * Commission a credential for this refinement's container. Throws
   * {@code AgentCredentialException} rather than answering null — the failure belongs at the
   * ensure that could not produce one.
   *
   * <p><b>The two arguments carry different facts and that is why there are two.</b> The refinement
   * id is the {@code contextId} — which container this credential belongs to, and what the reconcile
   * compares against live refinements — while {@code projectId} is the scope: the {@code project}
   * claim qits-idp puts on every token the pair mints, and what a resource service judges it on.
   * Unlike the agent harness, where the context and the scope are the same string, a refinement's
   * are not.
   */
  Commissioned commission(long refinementId, String projectId);

  /** Give a credential back. Best-effort and never throws. */
  void decommission(String clientId);

  /** Every live commission of kind {@link #CONTEXT_KIND}. Empty covers "could not ask". */
  List<Commission> listRefinementCommissions();
}
