package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Refinement;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The refinement rows, plain CRUD. <b>No transaction is opened here</b> — the caller owns it, the
 * same split every repository in this package keeps.
 */
@ApplicationScoped
public class RefinementRepository implements PanacheRepositoryBase<Refinement, Long> {

  /** The refinement of one epic, if it exists. The find half of find-or-create. */
  public Optional<Refinement> findByEpic(String epicId) {
    return find("epicId", epicId).firstResultOptional();
  }

  /** Every refinement holding a commissioned credential — the reconcile's local inventory. */
  public List<Refinement> holdingACredential() {
    return list("commissionedClientId is not null");
  }

  /** A project's refinements, oldest first — the activity bar's row and the find-only read. */
  public List<Refinement> listByProject(String projectId) {
    return list("projectId = ?1 order by createdAt, id", projectId);
  }
}
