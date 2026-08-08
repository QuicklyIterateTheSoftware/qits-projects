package eu.wohlben.qits.epics.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Akin to today's {@code feature-ideas}, owned by an epic. {@code epicId} is a real intra-module FK
 * (cascade-deleted with the epic); {@code dependsOnFeatureId} is a nullable self-reference.
 */
@Entity
public class Feature extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "epic_id", nullable = false)
  public String epicId;

  /** Short label for lists/breadcrumbs. */
  @Column(nullable = false)
  public String title;

  /**
   * Git-safe path segment, minted from the title at create and never changed after: it names the
   * feature's branch {@code feature/<epic-slug>/<slug>}. Unique within the epic.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  /** The long-form Markdown body. */
  public String description;

  /** Nullable self-reference to another feature this one depends on. */
  @Column(name = "depends_on_feature_id")
  public String dependsOnFeatureId;

  /** Set when the feature ships; null while unimplemented. */
  @Column(name = "implemented_on")
  public Instant implementedOn;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
