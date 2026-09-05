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

  /**
   * The claim name qits-idp scopes a commissioned credential by, and the one every resource service
   * reads it back under ({@code QitsClaims.PROJECT}). Spelled as a constant for that library's
   * reason: a typo in a claim name reads as "no claim", which is a credential that quietly keeps the
   * wider grant rather than a failure anybody sees.
   */
  String PROJECT_CLAIM = "project";

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
   * <p><b>The project is both halves of what qits-idp is told.</b> It is the {@code contextId} — the
   * name of the container this credential belongs to, which the reconcile compares against live
   * places — and it is the {@link #PROJECT_CLAIM} the commission states, which is the scope every
   * resource service judges the credential on. The two happen to be the same string here and are
   * different facts: an agent container's context IS a project, and what it may act on is that
   * project. qits-ci's manual trigger reads the claim to decide which repositories a caller may have
   * evaluated, so an agent reaches its own project's pipelines and nobody else's.
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
