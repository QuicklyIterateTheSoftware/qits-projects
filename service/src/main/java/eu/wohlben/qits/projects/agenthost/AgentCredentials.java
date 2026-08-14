package eu.wohlben.qits.projects.agenthost;

import java.util.List;

/**
 * The commission API of qits-idp, as this harness needs it: a credential per agent container, given
 * back when the container ends.
 *
 * <p>The sole implementation is {@link eu.wohlben.qits.projects.idphost.IdpAgentCredentials}, which
 * is {@code @DefaultBean} — so the suite installs a double and reaches no idp, the same arrangement
 * {@code ContainerRuntime} has.
 *
 * <p><b>Absent is a supported configuration and it is the shipped one.</b> With
 * {@code quarkus.oidc-client.client-enabled=false} this service holds no credential of its own, so
 * it can authenticate to nothing and commissions nothing: {@link #enabled()} answers false, no HTTP
 * call is made, no environment is injected, and a container's spec is byte for byte the spec it was
 * before any of this existed. That is not a degraded mode — it is the deployment that has no idp,
 * and it must stay indistinguishable.
 *
 * <h2>The context</h2>
 *
 * <p>A commission names {@code (contextKind, contextId)}, and this harness's pair is
 * {@code (agent-container, <projectId>)}. The kind is the platform's word for what the credential
 * belongs to; the id is the project, because a place is {@code owner/project-agent/<projectId>} and
 * that id is the only name for a container that cannot go stale.
 */
public interface AgentCredentials {

  /** The context kind every commission this harness makes carries. Storage, on idp's rows. */
  String CONTEXT_KIND = "agent-container";

  /** A freshly commissioned credential. The secret is answered once and never again. */
  record Commissioned(String clientId, String secret) {}

  /** One live commission, as the reconcile reads it back. No secret, ever. */
  record Commission(String clientId, String projectId) {}

  /**
   * Whether this deployment commissions at all — see the class javadoc. Read before anything else,
   * so a deployment without an idp makes no call and gets the old behaviour exactly.
   */
  boolean enabled();

  /**
   * Commission a credential for this project's agent container.
   *
   * <p>Throws {@link AgentCredentialException} and never answers null: a container that should hold
   * a credential and does not is a container whose reads will be refused later, a long way from
   * here, so the failure belongs at the ensure that could not produce one.
   */
  Commissioned commission(String projectId);

  /**
   * Give a credential back. <b>Best-effort and never throws</b> — the caller is either a fresh
   * provision replacing a credential nothing holds any more, or the reconcile, which comes round
   * again. A client id idp no longer has is a success, not a failure.
   */
  void decommission(String clientId);

  /**
   * Every live commission of kind {@link #CONTEXT_KIND} this service owns.
   *
   * <p><b>Empty is "nothing to reconcile", including when nobody answered.</b> The reconcile only
   * ever <em>removes</em> what this list names, so an unreadable listing costs a pass rather than
   * reaping something on an answer nobody gave.
   */
  List<Commission> listAgentContainerCommissions();
}
