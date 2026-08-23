package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One epic's refinement container, as this service hosts it — the row behind the projects SPA's
 * refining route, which used to be an ordinary qits-workspaces workspace on a {@code refining/*}
 * branch.
 *
 * <p><b>Keyed by epic, addressed by row id.</b> An epic has at most one refinement (the unique
 * constraint on {@code epic_id} is what makes find-or-create race-safe), and every URL — lifecycle
 * verbs, the daemon proxy, the SSE channel — carries the numeric row id, the same shape the
 * workspaces domain used and the shape the SPA's panels already take.
 *
 * <p><b>The epic id is a key, not a relation.</b> The epic lives in the {@code epics} module's own
 * database and Flyway lineage; a foreign key across persistence units is not a thing, and the epics
 * module deliberately depends on nothing here. The row is torn down by an explicit discard, never
 * by a cascade from a table it cannot see.
 *
 * <p><b>The commissioned credential is two columns, and that is forced rather than chosen.</b> The
 * pair reaches the container as environment, and qits-containers hashes a workload's whole spec —
 * environment included — to decide whether an {@code ensure} may start the container in place or
 * must replace it. A wake that could not reproduce the same two values byte for byte would be a
 * spec change destroying the container on every resume. The same argument, at length, on {@link
 * AgentCredential}.
 *
 * <p><b>A {@link CausedRow}.</b> The insert runs on the request thread that opened the refining
 * route, so the stamp records what asked for it.
 */
@Entity
@Table(name = "refinement")
@EntityListeners(CausationStamp.class)
public class Refinement extends PanacheEntityBase implements CausedRow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  /** The epic being refined. Unique — an epic has at most one refinement. */
  @Column(name = "epic_id", nullable = false, unique = true)
  public String epicId;

  /** The project the epic belongs to. A key into another table of this database, kept plain. */
  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** The project's wrapper repository — what the container clones and the branch lives on. */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /** The refinement's branch on the wrapper: {@code refining/<epicSlug>}. */
  @Column(name = "branch", nullable = false)
  public String branch;

  /** The branch the refinement forked from — the wrapper's default branch at create time. */
  @Column(name = "parent", nullable = false)
  public String parent;

  /**
   * A human-readable label ({@code refining-<epicSlug>}, sanitized), announced to the daemon as its
   * workspace id so logs and frames read well. Decoration — the row id is the address.
   */
  @Column(name = "label", nullable = false)
  public String label;

  /**
   * The context the refinement chat opens with: the epic's title, description and outline as
   * markdown, computed from the epic tree when the refinement is created. Host-side metadata with
   * no source inside the container.
   */
  @Column(name = "preamble", columnDefinition = "text")
  public String preamble;

  /** The commissioned idp client this refinement's container holds, or null before any container. */
  @Column(name = "commissioned_client_id", columnDefinition = "text")
  public String commissionedClientId;

  /** Its secret. See the class javadoc for why this is a column at all. */
  @Column(name = "commissioned_client_secret", columnDefinition = "text")
  public String commissionedClientSecret;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

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
