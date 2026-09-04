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
   * merge-request shape of the whole aggregate. CONFLICTED is in it for exactly the same reason —
   * the next push that makes the fold succeed clears it, and a request nothing re-merges is a
   * request nothing can ever clear.
   */
  public static final List<ReleaseRequest.State> OPEN =
      List.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.FAILED,
          ReleaseRequest.State.REJECTED,
          ReleaseRequest.State.CONFLICTED);

  /**
   * The open request of this repository that already names {@code branch} as a source, if any —
   * what the converge-on-create rule converges on, and what a push to that branch re-merges.
   *
   * <p>A subquery rather than a join: the child rows are not mapped as an association (the parent is
   * loaded by id, never navigated), and this read wants the parent alone.
   */
  public Optional<ReleaseRequest> findOpenByBranch(String repoId, String branch) {
    return findOpenByBranches(repoId, List.of(branch)).stream().findFirst();
  }

  /**
   * Every open request of this repository naming any of {@code branches} as a named source, oldest
   * first. The push consumption's read: one push touches one branch and may participate in several
   * requests, and <b>each of them re-merges on its own</b> — a shared trigger is never a shared
   * merge.
   */
  public List<ReleaseRequest> findOpenByBranches(String repoId, Collection<String> branches) {
    if (branches.isEmpty()) {
      return List.of();
    }
    return list(
        "repoId = ?1 and state in ?2 and id in"
            + " (select s.requestId from ReleaseRequestSource s where s.name in ?3)"
            + " order by createdAt",
        repoId,
        OPEN,
        branches);
  }

  /**
   * Every request still pending a verdict for one commit — what an arriving verdict resolves. The
   * commit is the request's <b>merged</b> sha: the fold is what CI built and what a verdict is
   * about.
   */
  public List<ReleaseRequest> findPendingByCommit(String repoId, String commitSha) {
    return list(
        "repoId = ?1 and mergedSha = ?2 and state = ?3",
        repoId,
        commitSha,
        ReleaseRequest.State.PENDING);
  }

  /** Every open request, oldest first — the sweep's worklist. */
  public List<ReleaseRequest> listOpen() {
    return list("state in ?1 order by createdAt", OPEN);
  }

  /**
   * Every open request of one repository, oldest first — the worklist of a trigger that is about
   * the <b>repository</b> rather than about a branch: a sibling release adding an implicit tag, or
   * one reaching {@code main} and leaving the set.
   */
  public List<ReleaseRequest> listOpenByRepo(String repoId) {
    return list("repoId = ?1 and state in ?2 order by createdAt", repoId, OPEN);
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
