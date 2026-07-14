package eu.wohlben.qits.domain.repository.entity;

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
 * A submodule edge between two repositories under the same project: {@code parent} is the
 * superproject, {@code child} is the repository imported to satisfy one of its {@code .gitmodules}
 * entries. Modeled as a link entity (rather than a field on {@code Repository}) so one child can be
 * the submodule of several superprojects <em>and</em> be used standalone — which is what makes
 * per-project deduplication possible: a child referenced more than once within a project is
 * imported once and every reference is a separate edge to the same row.
 *
 * <p>The pinned commit is deliberately <em>not</em> stored — it lives in the superproject's gitlink
 * and is read at {@code git submodule update} time. {@code name} is the {@code .gitmodules} section
 * name, needed as the {@code submodule.<name>.url} config key at materialization; {@code path} is
 * the mount path within the parent working tree (diagnostics + {@code submodule update}). The
 * {@code .gitmodules} {@code branch =} key is not stored — this feature pins tips via the gitlink
 * and does not do {@code submodule update --remote}.
 */
@Entity
@Table(
    name = "repository_submodule",
    uniqueConstraints =
        @UniqueConstraint(
            name = "UK_repository_submodule_parent_path",
            columnNames = {"parent_repo_id", "path"}))
public class RepositorySubmodule extends PanacheEntityBase {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  public String id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "parent_repo_id", nullable = false)
  public Repository parent;

  @ManyToOne(optional = false)
  @JoinColumn(name = "child_repo_id", nullable = false)
  public Repository child;

  /** The mount path of the submodule within the parent working tree (the {@code path =} key). */
  @Column(nullable = false)
  public String path;

  /** The {@code .gitmodules} section name — the {@code submodule.<name>.url} config key. */
  @Column(nullable = false)
  public String name;
}
