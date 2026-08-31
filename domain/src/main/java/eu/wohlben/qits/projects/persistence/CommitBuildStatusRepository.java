package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.CommitBuildStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The per-commit build-status ledger's rows.
 *
 * <p>Plain CRUD and nothing else. <b>No transaction is opened here</b> — the caller owns it: the
 * write comes from {@code BuildStatusLedger} under the durable consumption, the reads from the
 * repositories API on a request thread.
 */
@ApplicationScoped
public class CommitBuildStatusRepository
    implements PanacheRepositoryBase<CommitBuildStatus, String> {

  /** Every verdict for one commit, newest run first. */
  public List<CommitBuildStatus> findByCommit(String repoId, String commitSha) {
    return list(
        "repoId = ?1 and commitSha = ?2 order by finishedAt desc, runId desc", repoId, commitSha);
  }

  /**
   * Record one run's verdict, replacing whatever that run had — delete-then-insert, the {@code
   * AgentCredentialRepository.put} shape, so a replayed or corrected announcement converges on one
   * row per run rather than colliding with the primary key.
   */
  public void put(CommitBuildStatus row) {
    deleteById(row.runId);
    persist(row);
  }
}
