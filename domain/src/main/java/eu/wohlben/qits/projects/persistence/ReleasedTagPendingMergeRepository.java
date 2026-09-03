package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The released tags of every repository and whether each has reached {@code main}. Two questions
 * only: what is still in flight for a repository (the implicit source set), and the row for one tag.
 */
@ApplicationScoped
public class ReleasedTagPendingMergeRepository
    implements PanacheRepositoryBase<ReleasedTagPendingMerge, String> {

  /**
   * The repository's releases still in flight — released, not yet on {@code main} — oldest first.
   * This <b>is</b> the implicit source set: every open request of the repository folds these in, so
   * that a release cannot be a step backwards from one already shipping.
   */
  public List<ReleasedTagPendingMerge> listPending(String repoId) {
    return list("repoId = ?1 and mergedAt is null order by releasedAt", repoId);
  }

  public Optional<ReleasedTagPendingMerge> find(String repoId, String tagName) {
    return find("repoId = ?1 and tagName = ?2", repoId, tagName).firstResultOptional();
  }
}
