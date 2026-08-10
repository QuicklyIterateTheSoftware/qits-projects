package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Glues a feature to a concrete repository, owned by a feature. {@code featureId} is a real
 * intra-module FK (cascade-deleted with the feature); {@code repositoryId} references {@code
 * domain}'s {@code Repository} by String id — no JPA {@code @ManyToOne} and no cross-DB FK;
 * existence is validated in the {@code service} controller. {@code dependsOnTaskId} is a nullable
 * self-reference.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason {@link Epic} gives — and the one of the three most
 * likely to be machine-minted, since a task is what an agent creates when it decides a feature
 * needs work in a concrete repository ({@code EpicMcpTools.createTask}).
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Task extends PanacheEntityBase implements CausedRow {

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
