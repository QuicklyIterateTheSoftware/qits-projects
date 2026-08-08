package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class EpicRepository implements PanacheRepositoryBase<Epic, String> {

  /** Oldest first — the same order V2's slug backfill ranked duplicates by. */
  public List<Epic> listByProject(String projectId) {
    return find("projectId", Sort.by("createdAt").and("id"), projectId).list();
  }

  /** The same list narrowed to one phase — the (project_id, status) index V3 adds. */
  public List<Epic> listByProjectAndStatus(String projectId, EpicStatus status) {
    return find(
            "projectId = ?1 and status = ?2",
            Sort.by("createdAt").and("id"),
            projectId,
            status)
        .list();
  }
}
