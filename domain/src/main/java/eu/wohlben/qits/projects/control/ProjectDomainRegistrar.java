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
 */
public interface ProjectDomainRegistrar {

  /**
   * Register (or replace) the record {@code domain}/{@code type} so that it resolves to {@code
   * value}.
   */
  void register(
      String projectId, String slug, String domain, ProjectDnsRecordType type, String value);
}
