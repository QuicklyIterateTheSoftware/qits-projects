package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The idp client commissioned <b>for one project's agent container</b> — the credential that
 * container authenticates the platform's own reads with, held here so the host can hand it back when
 * the container ends.
 *
 * <p>One row per project, keyed on the project id, minted when a <em>fresh</em> container is
 * provisioned and removed when that container is gone. Its lifetime is the container's, which is the
 * whole model: no TTL, no refresh, nothing durable outliving the thing it authenticates.
 *
 * <p><b>The secret is stored, and that is forced rather than chosen.</b> The credential reaches the
 * container as environment, and qits-containers hashes a workload's whole spec — environment
 * included — to decide whether an {@code ensure} may start the container in place or must replace
 * it. So a wake that could not reproduce the same two values byte for byte would be a spec change,
 * and every wake would recreate the container: exactly the defect {@code
 * AgentContainerFactory.forRestart} records and the reason nothing per-call may enter that spec. The
 * row is what lets the wake arm send the value the fresh arm sent. Read
 * {@code service/…/agenthost/AgentCommissions} before moving it.
 *
 * <p><b>No foreign key to {@link Project}</b>, unlike every other relation in this database. An
 * agent container outlives its project — deleting a project does not remove one — so a cascade here
 * would drop the row while the container it belongs to is still running and still holding the
 * credential, which is precisely the leak the reconcile exists to prevent. The project id is a key,
 * not a relation.
 *
 * <p><b>A {@link CausedRow}.</b> The insert runs on the request thread that asked for the container
 * — {@code POST …/agent-container/ensure} — so the stamp records what asked for it, the same reading
 * {@link Project} and {@link Repository} get. Nothing on the machine paths inserts: the reconcile
 * only deletes.
 */
@Entity
@Table(name = "agent_credential")
@EntityListeners(CausationStamp.class)
public class AgentCredential extends PanacheEntityBase implements CausedRow {

  /** The project whose agent container holds this credential. The key, never a relation. */
  @Id
  @Column(name = "project_id")
  public String projectId;

  /** The commissioned idp client id — what a decommission addresses. */
  @Column(name = "client_id", nullable = false)
  public String clientId;

  /** Its secret. See the class javadoc for why this is a column at all. */
  @Column(name = "client_secret", nullable = false)
  public String clientSecret;

  /** When it was commissioned. Read by nothing but a person looking at the table. */
  @Column(name = "commissioned_at", nullable = false)
  public Instant commissionedAt;

  /** The platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
