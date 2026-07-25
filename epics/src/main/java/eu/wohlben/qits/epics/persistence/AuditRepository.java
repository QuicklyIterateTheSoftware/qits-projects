package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditEntry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class AuditRepository implements PanacheRepositoryBase<AuditEntry, String> {

  // Newest first, with id as a deterministic tie-breaker so rows sharing a changed_at (e.g. several
  // writes in one transaction) come back in a stable order.
  private static final Sort NEWEST_FIRST =
      Sort.by("changedAt", Sort.Direction.Descending).and("id", Sort.Direction.Descending);

  /** All audit rows for one entity, newest first. */
  public List<AuditEntry> listForEntity(AuditEntityType type, String entityId) {
    return find("entityType = ?1 and entityId = ?2", NEWEST_FIRST, type, entityId).list();
  }

  /**
   * All audit rows for an epic subtree (epic + features + tasks share the epic_id), newest first.
   */
  public List<AuditEntry> listByEpic(String epicId) {
    return find("epicId", NEWEST_FIRST, epicId).list();
  }
}
