package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.RefinementDesign;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** The refinement designs, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class RefinementDesignRepository
    implements PanacheRepositoryBase<RefinementDesign, String> {

  /** Oldest first, the order the Design tab renders. */
  public List<RefinementDesign> listByRefinement(Long refinementId) {
    return list("refinementId = ?1 order by createdAt, id", refinementId);
  }

  public Optional<RefinementDesign> findByRefinementAndId(Long refinementId, String id) {
    return find("refinementId = ?1 and id = ?2", refinementId, id).firstResultOptional();
  }
}
