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
 * Akin to today's {@code feature-ideas}, owned by an epic. {@code epicId} is a real intra-module FK
 * (cascade-deleted with the epic); {@code dependsOnFeatureId} is a nullable self-reference.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason {@link Epic} gives: {@code FeatureService.create}
 * is reached from the SPA and from {@code EpicMcpTools} alike, on the request thread, where the
 * scope the causation filter restored is still standing. The copies a supersede makes are written
 * on that same thread and inherit the same cause, which is right — the successor draft exists
 * because of whatever superseded the old epic.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Feature extends PanacheEntityBase implements CausedRow {

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
