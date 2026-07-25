package eu.wohlben.qits.epics.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The planning spine (one per docs-epic today), owned by a project. {@code projectId} references
 * {@code domain}'s {@code Project} by String id — no JPA {@code @ManyToOne} and no cross-DB FK
 * (epics is a separate physical DB); existence is validated in the {@code service} controller.
 */
@Entity
public class Epic extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** Short label for lists/breadcrumbs. */
  @Column(nullable = false)
  public String title;

  /** The long-form Markdown spine. */
  public String description;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
