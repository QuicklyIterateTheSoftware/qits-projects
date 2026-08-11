package eu.wohlben.qits.epics.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditEntry;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.error.EpicsException;
import eu.wohlben.qits.epics.persistence.AuditRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Writes and reads the append-only audit log — the git replacement. {@link #record} is called from
 * every mutating epic/feature/task service method and joins that method's transaction, so the audit
 * row commits atomically with the change. The snapshot is a JSON copy of the entity at the moment
 * of the change (serialized with the app {@link ObjectMapper}). Every row carries the owning {@code
 * epicId} so the whole subtree's history is queryable by one column even after the live rows are
 * deleted.
 */
@ApplicationScoped
public class AuditService {

  @Inject AuditRepository auditRepository;

  @Inject ObjectMapper objectMapper;

  @Inject ReadPatience patience;

  @Transactional
  public void record(
      AuditEntityType entityType,
      String entityId,
      String epicId,
      AuditOperation operation,
      String changedBy,
      Object snapshot) {
    // Flush pending entity changes first so Hibernate's @CreationTimestamp/@UpdateTimestamp
    // (applied
    // at flush) are populated before we snapshot — otherwise the snapshot captures a stale/absent
    // updated_at. Shares the epics persistence unit with the domain entities, so this flushes them.
    auditRepository.getEntityManager().flush();
    AuditEntry entry = new AuditEntry();
    entry.id = UUID.randomUUID().toString();
    entry.entityType = entityType;
    entry.entityId = entityId;
    entry.epicId = epicId;
    entry.operation = operation;
    entry.changedBy = changedBy;
    entry.snapshot = serialize(snapshot);
    auditRepository.persist(entry);
  }

  /**
   * Audit history for one entity, newest first, held through a postgres cutover ({@link
   * ReadPatience}). This log is the git replacement: an empty history reads as "nothing ever
   * happened to this row", which is the one wrong answer it must never give. Both reads are
   * transactionless GETs, unlike {@link #record} above, which joins its caller's write.
   */
  public List<AuditEntry> listForEntity(AuditEntityType type, String entityId) {
    return patience.hold(
        "entity audit history", () -> auditRepository.listForEntity(type, entityId));
  }

  /** Audit history for an entire epic subtree (epic + its features + tasks), newest first. */
  public List<AuditEntry> listForEpic(String epicId) {
    return patience.hold("epic audit history", () -> auditRepository.listByEpic(epicId));
  }

  private String serialize(Object snapshot) {
    if (snapshot == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException e) {
      throw new EpicsException(500, "Failed to serialize audit snapshot", e);
    }
  }
}
