package eu.wohlben.qits.epics.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One append-only audit row per create/update/delete of an epic/feature/task — the git replacement
 * ("who changed what, when"). Deliberately NOT FK'd back to the live entity (a DELETE row must
 * survive the row it describes). {@code snapshot} is a JSON copy of the entity's changed/current
 * fields.
 */
@Entity
public class AuditEntry extends PanacheEntityBase {

  @Id public String id;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false)
  public AuditEntityType entityType;

  @Column(name = "entity_id", nullable = false)
  public String entityId;

  /**
   * The owning epic id for every row (an epic's own rows carry their own id). Lets the epic audit
   * endpoint query the whole subtree's history by a single column — surviving deletion of the live
   * rows, which live-row joins could not.
   */
  @Column(name = "epic_id", nullable = false)
  public String epicId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public AuditOperation operation;

  /** The authenticated principal that made the change (null if unattributed). */
  @Column(name = "changed_by")
  public String changedBy;

  @CreationTimestamp
  @Column(name = "changed_at", nullable = false, updatable = false)
  public Instant changedAt;

  /** JSON snapshot of the entity's fields at the time of the change. */
  public String snapshot;
}
