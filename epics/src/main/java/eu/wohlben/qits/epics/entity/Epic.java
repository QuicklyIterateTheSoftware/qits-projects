package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The planning spine (one per docs-epic today), owned by a project. {@code projectId} references
 * {@code domain}'s {@code Project} by String id — no JPA {@code @ManyToOne} and no cross-DB FK
 * (epics is a separate physical DB); existence is validated in the {@code service} controller.
 *
 * <p><b>A {@link CausedRow}.</b> Epics are minted by agents as much as by people: {@code
 * EpicMcpTools.createEpic} is the same {@code EpicService.create} the SPA reaches, on the same
 * request thread, so the {@code CausationStamp} listener reads the scope the {@code
 * X-Qits-Causation-Id} filter restored and the row records what asked for this epic. A person
 * typing into the SPA sends no header and leaves a rootless row, which is the correct answer rather
 * than a missing one.
 *
 * <p>The {@link AuditEntry} written beside every change is a {@code CausedRow} too, and the two are
 * not the same fact: this column answers "what caused this epic to exist", insert-only and forever;
 * the audit log answers the same question for every later update and for the delete, after this row
 * is gone.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Epic extends PanacheEntityBase implements CausedRow {

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
