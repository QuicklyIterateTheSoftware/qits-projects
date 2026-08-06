package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;

/**
 * "This project's hostname should resolve here" — handed to whoever is authoritative for the name.
 * In this platform that is qits-dns, which owns the zones and is the only thing that knows which of
 * them a given name falls in (main-environment-plan.md §1).
 *
 * <p><strong>A port, not an implementation</strong>, and optional like every port here: a
 * deployment with no nameserver of its own stores the record and registers nothing, which is
 * exactly what a project whose domain is managed at a registrar's control panel wants.
 *
 * <p><b>Fire-and-forget, and that is a contract on both sides.</b> Called by {@code
 * ProjectService.create} <em>after</em> the creating transaction commits, so an implementation that
 * reads the project back sees it; and an implementation that throws is logged and swallowed,
 * because a project must never fail to exist because a sibling service was down.
 *
 * <p><b>The zone is not this context's to invent.</b> An implementation resolves the name against
 * the zones that exist and, finding none that contains it, must warn and stop — a zone is a
 * registrar-level fact (NS delegation, glue) and creating one because a project asked would produce
 * a zone nothing on the internet points at.
 *
 * <p>Ids and values only. {@code slug} travels for the sake of the log line an operator reads when
 * a registration is skipped; nothing about the record is derived from it.
 *
 * <p><b>Two methods, one registration.</b> {@link #register} is the creation path and stays
 * fire-and-forget; {@link #registerNow} is the same write made <em>synchronously</em> for the
 * manual reconcile (main-environment-plan.md §5), and both must be served by one piece of logic —
 * one url builder, one payload, one zone resolution. A remedy that can drift from what it repairs
 * is not a remedy.
 */
public interface ProjectDomainRegistrar {

  /**
   * Register (or replace) the record {@code domain}/{@code type} so that it resolves to {@code
   * value}.
   */
  void register(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value);

  /**
   * Make the same registration <b>now</b>, and answer with what happened — the synchronous half of
   * this port, driven by {@code ProjectReconcileService} on a request thread. Three obligations
   * follow from that thread:
   *
   * <ul>
   *   <li><b>Bounded.</b> An implementation must carry its own connect and request timeouts; a
   *       person waiting on an answer is the deadline.
   *   <li><b>Total.</b> Every failure is a {@link ProjectReconciliation.DomainOutcome#FAILED} with a
   *       reason, not a thrown exception — a receiver being down is the answer, not an error in
   *       answering. A thrown exception is still caught by the caller, but as a defect rather than
   *       as the contract.
   *   <li><b>Idempotent.</b> The receiver being idempotent is what makes re-asserting legitimate at
   *       all.
   * </ul>
   *
   * <p>The documented stop becomes a reportable outcome here: no zone containing the name is {@link
   * ProjectReconciliation.DomainOutcome#NO_MATCHING_ZONE} with <b>no write attempted</b>, which is
   * the same refusal to invent a zone, said out loud instead of into a log.
   *
   * <p>Never {@link ProjectReconciliation.DomainOutcome#NOT_CONFIGURED}: whether the project has a
   * record at all is decided before this port is reached, because a registrar handed three nulls
   * would have to guess what the absence meant.
   */
  ProjectReconciliation.DomainAssertion registerNow(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value);
}
