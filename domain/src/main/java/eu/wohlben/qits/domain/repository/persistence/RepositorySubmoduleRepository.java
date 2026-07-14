package eu.wohlben.qits.domain.repository.persistence;

import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class RepositorySubmoduleRepository
    implements PanacheRepositoryBase<RepositorySubmodule, String> {

  /** The submodule edges whose superproject is {@code parentId} (its imported children). */
  public List<RepositorySubmodule> findByParentId(String parentId) {
    return list("parent.id", parentId);
  }

  /**
   * Whether the superproject {@code parentId} already has an edge at mount {@code path} — the
   * natural key of a submodule reference (matching the DB unique constraint), so re-importing a
   * superproject is idempotent.
   */
  public boolean existsByParentAndPath(String parentId, String path) {
    return count("parent.id = ?1 and path = ?2", parentId, path) > 0;
  }
}
