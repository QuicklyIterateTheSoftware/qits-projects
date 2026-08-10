package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.List;
import java.util.UUID;

/**
 * The aggregate root: <b>one application that starts as a single wrapper repository and grows into
 * a polyrepository</b>. Its {@link #repositories} are the parts of that single app — microservices,
 * shared libraries, extracted fixtures — curated together by one maintainer, <b>not</b> an
 * aggregation of arbitrary third-party repos. This framing is load-bearing when reasoning about the
 * submodule/workspace code (name collisions within a project are the maintainer's own choice;
 * {@code origin} is a backup, not an authority): see the package doc ({@link
 * eu.wohlben.qits.projects}) and {@code docs/guides/project-model.md}.
 *
 * <p><b>A {@link CausedRow}.</b> Every project row is written on a request thread — {@code
 * ProjectService.create}, reached from {@code ProjectController} or from the MCP tool surface — so
 * the {@code CausationStamp} listener reads the scope the {@code X-Qits-Causation-Id} filter
 * restored and the column records what asked for the project. A browser create carries no header
 * and the row is rootless, which is the honest answer rather than a missing one; the self-seed at
 * boot ({@code SelfSeedService}) is rootless for the same reason — nothing caused it but the
 * process starting.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Project extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  @Column(nullable = false)
  public String name;

  /**
   * The git-safe, <b>immutable</b> and <b>unique</b> identity this project's wrapper repository is
   * named after ({@code <slug>-<slug>}), deliberately detached from the free-form, editable {@link
   * #name}.
   *
   * <p>Unique since V6, because each project has its own upstream backup organisation and this is
   * what names it. {@code ProjectService} suffixes a <em>derived</em> slug to the next free {@code
   * -2}, {@code -3}, … and refuses a <em>supplied</em> one that is taken with a 409 — the second is
   * a statement about which upstream the wrapper belongs to, and renaming it silently would break
   * that statement without saying so.
   *
   * <p>A repository's local alias must equal its remote basename for a committed <em>relative</em>
   * submodule url ({@code ../<name>.git}) to fold to the same thing in a workspace container and at
   * the forge — an invariant a renameable display name cannot carry. {@code updatable = false} is
   * what makes that structural: there is no rename path, so a wrapper's alias can never go stale.
   *
   * <p>Nullable in the schema (rows predating V44 are backfilled, and tests persist {@code new
   * Project()} directly); {@code ProjectService} enforces non-null and validates the format against
   * {@code ProjectSlug.PATTERN} on every create.
   */
  @Column(updatable = false)
  public String slug;

  public String description;

  /**
   * The domain this project resolves through, or {@code null} when it registers none — see {@link
   * ProjectDnsRecord}, which also carries why this field is a <b>declared placeholder</b> and what
   * deletes it.
   *
   * <p>Null for two ordinary reasons and not only one: rows predating the feature, and a self-seed
   * run with no domain configured. Hibernate reads an embeddable whose every column is null as a
   * null field, which is what makes both of those the same thing to every reader.
   */
  @Embedded public ProjectDnsRecord dns;

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<Repository> repositories;

  // SEAM (migration-plan.md §6, project <-> featureflow): the `featureFlowConfigurations`
  // @OneToMany is gone. domain.featureflow is monolith-only and deferred (§9 item 6) — it is
  // coupled to project in both directions and has no target repo — so there is no entity here to
  // point at and its tables are not in this context's database.
}
