package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentCredential;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The one-row-per-project table of commissioned agent-container credentials.
 *
 * <p>Plain CRUD and nothing else. <b>No transaction is opened here</b> — the caller
 * ({@code service/…/agenthost/AgentCommissions}) owns it, because the write happens on a request
 * thread that is in no transaction of its own and the reconcile's happens on a scheduler thread.
 */
@ApplicationScoped
public class AgentCredentialRepository implements PanacheRepositoryBase<AgentCredential, String> {

  /** The credential this project's agent container holds, if one was commissioned for it. */
  public Optional<AgentCredential> findByProject(String projectId) {
    return findByIdOptional(projectId);
  }

  /** Every project a credential is held for — the local half of the reconcile's inventory. */
  public List<String> projectsHoldingACredential() {
    return listAll().stream().map(row -> row.projectId).toList();
  }

  /**
   * Record the pair a fresh container was started with, replacing whatever this project held.
   *
   * <p>Delete-then-insert rather than an update, so the row a container holds is always a row that
   * was <em>written for that container</em> — an update would leave the old
   * {@code commissioned_at} plausible while the id beside it had changed.
   */
  public void put(String projectId, String clientId, String clientSecret) {
    deleteById(projectId);
    AgentCredential row = new AgentCredential();
    row.projectId = projectId;
    row.clientId = clientId;
    row.clientSecret = clientSecret;
    row.commissionedAt = Instant.now();
    persist(row);
  }

  /** Forget this project's credential. Idempotent — a project holding none is not an error. */
  public void forget(String projectId) {
    deleteById(projectId);
  }
}
