package eu.wohlben.qits.epics.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  /**
   * Git-safe path segment, minted from the title at create and never changed after: it names the
   * epic's branch {@code epic/<slug>} and prefixes every feature and task branch below it. Unique
   * within the project.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  /**
   * The phase this epic is in (V3). Decides which mutations the services accept: structural edits
   * need {@link EpicStatus#REFINING}, implemented markers need {@link EpicStatus#IMPLEMENTATION},
   * and the two terminal statuses accept neither.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public EpicStatus status;

  /**
   * The successor draft this epic spawned when it was superseded; null on every other row. The old
   * scope stays here as the record of what was discarded, which is why superseded epics remain
   * list entries.
   */
  @Column(name = "superseded_by_epic_id")
  public String supersededByEpicId;

  /** The long-form Markdown spine. */
  public String description;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
