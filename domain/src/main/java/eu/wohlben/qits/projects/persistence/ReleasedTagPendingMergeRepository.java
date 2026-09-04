package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The released tags of every repository and whether each has reached {@code main}. The questions are
 * the flow's own: what is still in flight for a repository (the implicit source set), the row for
 * one tag, the rows a version names across the whole platform (the publish phase's correlation), and
 * what is owed a merge (the publish phase's sweep).
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

  /**
   * Every repository's row for one tag name, whatever state it is in — the correlation {@code
   * DeploymentActive} forces, since that event names an application and a version and no repository.
   *
   * <p>A list rather than an {@code Optional} because the uniqueness is a fact about the platform
   * (one calver, stamped to the second, refused as {@code tag-exists} on a collision) and not a
   * constraint in this table, whose unique key is {@code (repo_id, tag_name)}. The caller decides
   * what a second row means; answering the first would be a guess.
   */
  public List<ReleasedTagPendingMerge> listByTag(String tagName) {
    return list("tagName = ?1 order by releasedAt", tagName);
  }

  /**
   * Everything gated and not landed: the merges this service owes {@code main}. The publish phase's
   * sweep is the only caller, and a row is here exactly while the git host has not applied it.
   */
  public List<ReleasedTagPendingMerge> listOwedMerges() {
    return list("mergeRequestedAt is not null and mergedAt is null order by mergeRequestedAt");
  }

  /** The tags a page of release requests produced, for naming what reached {@code main}. */
  public List<ReleasedTagPendingMerge> listByRequests(List<String> requestIds) {
    if (requestIds.isEmpty()) {
      return List.of();
    }
    return list("releaseRequestId in ?1", requestIds);
  }

  /**
   * {@code list} and not {@code find}, deliberately: this class declares its own two-String {@code
   * find(repoId, tagName)}, which a {@code find("… = ?1", one)} call silently resolves to.
   */
  public Optional<ReleasedTagPendingMerge> findByRequest(String requestId) {
    return list("releaseRequestId = ?1", requestId).stream().findFirst();
  }
}
