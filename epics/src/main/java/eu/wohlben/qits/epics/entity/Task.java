package eu.wohlben.qits.epics.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Glues a feature to a concrete repository, owned by a feature. {@code featureId} is a real
 * intra-module FK (cascade-deleted with the feature); {@code repositoryId} references {@code
 * domain}'s {@code Repository} by String id — no JPA {@code @ManyToOne} and no cross-DB FK;
 * existence is validated in the {@code service} controller. {@code dependsOnTaskId} is a nullable
 * self-reference.
 */
@Entity
public class Task extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "feature_id", nullable = false)
  public String featureId;

  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /** Short label for lists/breadcrumbs. */
  @Column(nullable = false)
  public String title;

  /**
   * Git-safe path segment, minted from the title at create and never changed after: it names the
   * task's branch {@code task/<epic-slug>/<feature-slug>/<slug>}. Unique within the feature.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  /** The long-form Markdown body. */
  public String description;

  /** Nullable self-reference to another task this one depends on. */
  @Column(name = "depends_on_task_id")
  public String dependsOnTaskId;

  /** Set when the task is done; null while unimplemented. */
  @Column(name = "implemented_at")
  public Instant implementedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
