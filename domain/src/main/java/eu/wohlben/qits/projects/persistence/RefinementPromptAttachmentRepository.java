package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.RefinementPromptAttachment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** The refinement prompt attachments, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class RefinementPromptAttachmentRepository
    implements PanacheRepositoryBase<RefinementPromptAttachment, String> {

  /** Oldest first, the order the panel renders. */
  public List<RefinementPromptAttachment> listByRefinement(Long refinementId) {
    return list("refinementId = ?1 order by createdAt, id", refinementId);
  }

  public Optional<RefinementPromptAttachment> findByRefinementAndId(Long refinementId, String id) {
    return find("refinementId = ?1 and id = ?2", refinementId, id).firstResultOptional();
  }

  public void deleteByRefinement(Long refinementId) {
    delete("refinementId", refinementId);
  }
}
