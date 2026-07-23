package eu.wohlben.qits.domain.repository.entity;

import eu.wohlben.qits.domain.project.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * An addressable <em>name alias</em> for a repository within a project: {@code (project, name) →
 * repository}. The git host resolves {@code /git/<projectId>/<name>} through this table to the
 * repository's internal id, then serves {@code <data-dir>/<id>/origin}.
 *
 * <p>Modeled as a link table (not a {@code name} column on {@code Repository}) on purpose: a
 * repository's <b>technical identity</b> stays its opaque UUID id, deduped on exact {@code url},
 * and it may carry <b>as many names as there are links to it</b>. A name is derived from the
 * referencing url's basename — the top-level repo's own url, and each superproject's committed
 * submodule url (whose basename is what git requests when resolving a relative {@code
 * ../<name>.git}). So two superprojects that reference the same underlying repository (same url)
 * under different names each add an alias, and both names resolve to the one repository.
 *
 * <p>This is what lets git's native relative submodule resolution work: with a project's repos
 * served as siblings under {@code /git/<projectId>/}, {@code ../<name>.git} lands on the correct
 * sibling with no {@code submodule.<name>.url} override.
 *
 * <p>Both FKs declare {@code on delete cascade}: {@code ProjectService.delete} removes a project's
 * repositories one at a time, so an alias must vanish with whichever endpoint is deleted first (the
 * same referential-integrity care {@code repository_submodule} takes). The unique {@code
 * (project_id, name)} makes alias registration idempotent and enforces one repository per name
 * within a project.
 */
@Entity
@Table(
    name = "repository_name",
    uniqueConstraints =
        @UniqueConstraint(
            name = "UK_repository_name_project_name",
            columnNames = {"project_id", "name"}))
public class RepositoryName extends PanacheEntityBase {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  public String id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  public Project project;

  @ManyToOne(optional = false)
  @JoinColumn(name = "repository_id", nullable = false)
  public Repository repository;

  /** The addressable segment — a url basename, no {@code .git} suffix. */
  @Column(nullable = false)
  public String name;
}
