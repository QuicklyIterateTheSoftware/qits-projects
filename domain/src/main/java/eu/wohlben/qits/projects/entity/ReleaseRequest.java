package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One ask to release a branch, settled by quality gates before anything merges — the asynchronous
 * replacement for calling the release door and hoping the build was green.
 *
 * <p><b>A request is about a sha.</b> The caller names {@code (branch, commitSha)} and every gate
 * evaluates that sha; a branch whose head moves past a pending request is not silently widened —
 * the open request is withdrawn when a new one arrives, and the caller re-requests. The execution
 * arm gains an expected-sha check when the door split lands; until then the small race between
 * "gated at X" and "the door merges the branch" is the same one the synchronous door always had.
 *
 * <p>The state machine: {@code PENDING → READY → RELEASED}; {@code PENDING → REJECTED} when a
 * gating verdict is red; {@code READY → FAILED → READY} around a mechanical execution failure; and
 * {@code PENDING|READY → WITHDRAWN} when superseded. Stored as a string with no check constraint,
 * the platform's usual reasoning.
 *
 * <p>A {@link CausedRow}: created on the request thread, so the stamp records what asked. Updates
 * (gate resolution, execution) are machine-driven and the stamp is insert-only — the verdicts that
 * resolved a request are their own caused rows in {@code commit_build_status}.
 */
@Entity
@Table(name = "release_request")
@EntityListeners(CausationStamp.class)
public class ReleaseRequest extends PanacheEntityBase implements CausedRow {

  /** How a request stands. Grows without a migration; see the class javadoc for the moves. */
  public enum State {
    PENDING,
    READY,
    RELEASED,
    REJECTED,
    FAILED,
    WITHDRAWN
  }

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The public address pair, for the execution door. Null on a repository with no name. */
  @Column(name = "project_id")
  public String projectId;

  @Column(name = "repo_name")
  public String repoName;

  @Column(nullable = false)
  public String branch;

  @Column(name = "commit_sha", nullable = false)
  public String commitSha;

  @Column(nullable = false)
  public String summary;

  /** Who asked — the forwarded identity, carried onto the execution call as the acting user. */
  @Column public String requester;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public State state;

  /** Why a request is REJECTED, FAILED or WITHDRAWN — a sentence for the person who asked. */
  @Column public String detail;

  /** The calver the release door answered with, once RELEASED. */
  @Column public String version;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

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
