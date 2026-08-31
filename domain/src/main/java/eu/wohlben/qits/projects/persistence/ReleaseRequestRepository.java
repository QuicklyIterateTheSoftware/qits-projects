package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The release requests' rows. Plain CRUD and nothing else — the caller ({@code ReleaseRequests})
 * owns every transaction, because the writers are a request thread, the bus consumption and the
 * sweep, and each brackets itself.
 */
@ApplicationScoped
public class ReleaseRequestRepository implements PanacheRepositoryBase<ReleaseRequest, String> {

  /** The states a request can still move out of — what a supersede withdraws and a sweep visits. */
  private static final List<ReleaseRequest.State> OPEN =
      List.of(ReleaseRequest.State.PENDING, ReleaseRequest.State.READY, ReleaseRequest.State.FAILED);

  /** The open request for one branch, if any — at most one by the supersede-on-create rule. */
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
}
