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
 * <p><b>A request tracks its branch, merge-request-shaped.</b> {@code commitSha} is the head
 * currently being gated, and a new push to the branch <b>re-arms</b> the open request onto the new
 * head: gates invalidated, state back to PENDING, the same row — pushing a fix onto a rejected
 * request is the ordinary way to answer it, never a reason to open a second one. What a moving
 * head must never do is release commits nothing gated, and it cannot: every gate evaluates {@code
 * commitSha}, execution is pinned to it ({@code HEAD_MOVED} at the door otherwise), and the
 * re-arm is what moves it.
 *
 * <p>The state machine: {@code PENDING → READY → RELEASED}; {@code PENDING → REJECTED} when a
 * gating verdict is red; {@code READY → FAILED → READY} around a mechanical execution failure; a
 * new head re-arms {@code REJECTED} and {@code FAILED} back to {@code PENDING}; {@code WITHDRAWN}
 * is reserved for an explicit withdrawal. Stored as a string with no check constraint, the
 * platform's usual reasoning.
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

  /** The head currently being gated — moved by a re-arm, never silently outrun. */
  @Column(name = "commit_sha", nullable = false)
  public String commitSha;

  /**
   * When {@link #commitSha} was armed — creation or the latest re-arm. The settle window's basis:
   * a re-armed request waits its own window, and a no-ci push (which will never produce a verdict)
   * still passes vacuously after it.
   */
  @Column(name = "armed_at", nullable = false)
  public Instant armedAt;

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
