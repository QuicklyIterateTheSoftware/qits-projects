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
 * <p><b>Fire-and-forget</b>, on the same terms as {@link ProjectEnvironmentNotifier}: called after
 * the creating transaction commits, and a throwing implementation is logged rather than failing the
 * creation.
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
   * this port, driven by {@code ProjectReconcileService} on a request thread and therefore bounded,
   * total and idempotent on the terms {@link ProjectEnvironmentNotifier#ensureEnvironment} spells
   * out.
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
