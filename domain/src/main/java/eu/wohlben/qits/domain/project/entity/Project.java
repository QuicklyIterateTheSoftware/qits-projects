package eu.wohlben.qits.domain.project.entity;

import eu.wohlben.qits.domain.featureflow.entity.FeatureFlowConfiguration;
import eu.wohlben.qits.domain.repository.entity.Repository;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.List;

/**
 * The aggregate root: <b>one application that starts as a single wrapper repository and grows into
 * a polyrepository</b>. Its {@link #repositories} are the parts of that single app — microservices,
 * shared libraries, extracted fixtures — curated together by one maintainer, <b>not</b> an
 * aggregation of arbitrary third-party repos. This framing is load-bearing when reasoning about the
 * submodule/workspace code (name collisions within a project are the maintainer's own choice;
 * {@code origin} is a backup, not an authority): see the package doc ({@link
 * eu.wohlben.qits.domain.repository}) and {@code docs/guides/project-model.md}.
 */
@Entity
public class Project extends PanacheEntityBase {

  @Id public String id;

  @Column(nullable = false)
  public String name;

  /**
   * The git-safe, <b>immutable</b> identity this project's wrapper repository is named after
   * ({@code <slug>-<slug>}), deliberately detached from the free-form, editable {@link #name}.
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

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<Repository> repositories;

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  public List<FeatureFlowConfiguration> featureFlowConfigurations;
}
