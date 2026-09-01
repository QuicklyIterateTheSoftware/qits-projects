package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The release requests' rows. Plain CRUD and nothing else — the caller ({@code ReleaseRequests})
 * owns every transaction, because the writers are a request thread, the bus consumption and the
 * sweep, and each brackets itself.
 */
@ApplicationScoped
public class ReleaseRequestRepository implements PanacheRepositoryBase<ReleaseRequest, String> {

  /**
   * The states a request can still move out of — what a new head re-arms, what a sweep visits, and
   * what a list of "pending work" means when nobody named a state. REJECTED is in the set
   * deliberately: a rejected request comes back to life when the fix lands, which is the
   * merge-request shape of the whole aggregate.
   */
  public static final List<ReleaseRequest.State> OPEN =
      List.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.FAILED,
          ReleaseRequest.State.REJECTED);

  /** The open request for one branch, if any — at most one by the converge-on-create rule. */
  public Optional<ReleaseRequest> findOpenByBranch(String repoId, String branch) {
    return find("repoId = ?1 and branch = ?2 and state in ?3", repoId, branch, OPEN)
        .firstResultOptional();
  }

  /** Every request still pending a verdict for one commit — what an arriving verdict resolves. */
  public List<ReleaseRequest> findPendingByCommit(String repoId, String commitSha) {
    return list(
        "repoId = ?1 and commitSha = ?2 and state = ?3",
        repoId,
        commitSha,
        ReleaseRequest.State.PENDING);
  }

  /** Every open request, oldest first — the sweep's worklist. */
  public List<ReleaseRequest> listOpen() {
    return list("state in ?1 order by createdAt", OPEN);
  }

  /** One repository's requests, newest first. */
  public List<ReleaseRequest> listByRepo(String repoId) {
    return list("repoId = ?1 order by createdAt desc", repoId);
  }

  /**
   * One project's requests in the named states, across every repository it owns, <b>most recently
   * moved first</b>. Ordered by {@code updatedAt} rather than {@code createdAt} because this list is
   * read as a worklist: a re-armed request that has been waiting a week is the live one, and burying
   * it under a request created an hour ago and untouched since would be the wrong answer.
   *
   * <p>{@code projectId} is the column on the row, denormalised at creation. A repository with no
   * project has null there and appears in no project's list, which is correct — nothing addresses it
   * through a project.
   */
  public List<ReleaseRequest> listByProject(
      String projectId, Collection<ReleaseRequest.State> states) {
    if (states.isEmpty()) {
      return List.of();
    }
    return list("projectId = ?1 and state in ?2 order by updatedAt desc", projectId, states);
  }
}
