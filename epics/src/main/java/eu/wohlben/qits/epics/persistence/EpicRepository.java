package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Epic;
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
}
