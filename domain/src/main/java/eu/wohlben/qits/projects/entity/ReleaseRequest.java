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
 * One ask to release, settled by quality gates before anything ships — the asynchronous replacement
 * for calling the release door and hoping the build was green.
 *
 * <p><b>A request is an OCTOPUS MERGE of N sources, not a branch head.</b> Its participants are the
 * named branches on {@link ReleaseRequestSource} ({@code main} is implied by every create) plus the
 * repository's released tags not yet merged to {@code main} ({@link ReleasedTagPendingMerge}) —
 * folded into a backing branch of the request's own, {@code release/<id>}, by qits-githost's merge
 * primitive. {@link #mergedSha} is the tip of that fold: the one sha that is the whole of what would
 * be released, and therefore the one thing the gates evaluate and the execution is pinned to.
 *
 * <p><b>The re-merge is the re-arm.</b> Anything that changes what the fold would produce — a push
 * to a participating branch, a source added, a sibling release adding an implicit tag, a pending tag
 * reaching {@code main} — re-folds the sources and lands a new {@link #mergedSha}, which invalidates
 * the gates and puts the request back to PENDING. That is the same merge-request shape the single-
 * branch model had, generalised: pushing a fix onto a rejected request is still the ordinary way to
 * answer it. A fold that produces nothing new ({@code unchanged} at the git host) is not a change
 * and re-arms nothing.
 *
 * <p>The state machine: {@code PENDING → READY → RELEASED}; {@code PENDING → REJECTED} when a
 * gating verdict is red; {@code READY → FAILED → READY} around a mechanical execution failure —
 * retried by the sweep only while {@link #retryable} says asking again can change the answer;
 * {@code → CONFLICTED} when the sources cannot be folded at all, cleared by the next fold that
 * succeeds; a new merged sha re-arms {@code REJECTED}, {@code FAILED} and {@code CONFLICTED} back to
 * {@code PENDING}; {@code WITHDRAWN} is reserved for an explicit withdrawal. Stored as a string with
 * no check constraint, the platform's usual reasoning — which is why CONFLICTED cost no DDL.
 *
 * <p>A {@link CausedRow}: created on the request thread, so the stamp records what asked. Updates
 * (the re-merge, gate resolution, execution) are machine-driven and the stamp is insert-only — the
 * verdicts that resolved a request are their own caused rows in {@code commit_build_status}.
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
    /** The sources cannot be folded; {@link #conflictDetail} says which paths and whose head. */
    CONFLICTED,
    WITHDRAWN
  }

  /** The prefix of every request's backing branch. Storage, in the sense that git refs are. */
  public static final String BACKING_BRANCH_PREFIX = "release/";

  /**
   * The branch qits-githost folds this request's sources into — {@code release/<id>}, derived and
   * never stored, because the id already is the name and a column would be a second answer.
   */
  public String backingBranch() {
    return backingBranchOf(id);
  }

  /** The same derivation where only the id is in hand. */
  public static String backingBranchOf(String requestId) {
    return BACKING_BRANCH_PREFIX + requestId;
  }

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The public address pair, for the execution door. Null on a repository with no name. */
  @Column(name = "project_id")
  public String projectId;

  @Column(name = "repo_name")
  public String repoName;

  /**
   * The tip of the fold — what the gates evaluate and what an execution is pinned to. <b>Null until
   * the first merge lands</b>, and null is not "nothing to gate yet" guessed at: the gate reads it
   * as "not ready" and waits, rather than passing a request whose content nobody has computed.
   */
  @Column(name = "merged_sha")
  public String mergedSha;

  /**
   * Why a CONFLICTED request is conflicted: qits-githost's own 409 body, stored as the JSON document
   * it arrived as, so the API and the UI can put the conflicting paths and the head that introduced
   * each in front of a person. Null in every other state — cleared by the fold that succeeds.
   */
  @Column(name = "conflict_detail")
  public String conflictDetail;

  /**
   * When {@link #mergedSha} was armed — the merge that produced it. The settle window's basis: a
   * re-armed request waits its own window, and a no-ci push (which will never produce a verdict)
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

  /**
   * On a FAILED request: whether the sweep retries the execution. The executor classifies — a
   * failure of the moment (unreachable, 5xx, the door's retry-me 409s) is retried; a refusal about
   * the ask itself (ALREADY_INTEGRATED, a vanished branch) answers the same forever and waits for a
   * re-arm instead. Meaningless in every other state.
   */
  @Column(nullable = false)
  public boolean retryable;

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
