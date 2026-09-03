package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One released tag of a repository, and whether it has reached {@code main} yet.
 *
 * <p><b>Why this table exists.</b> A release is a tag; {@code main} is finalized only after the
 * deployment succeeds. Between those two moments the released commit sits on no branch, so a
 * release request opened in that window would be a step <em>backwards</em> from what is already
 * shipping — unless it folds that tag in too. The rows here with {@link #mergedAt} null are exactly
 * the repository's <b>implicit sources</b>: every open request of that repository merges them
 * alongside its named branches, which is what makes each release a superset of the releases still
 * in flight.
 *
 * <p><b>Rows are kept, never deleted.</b> A merged row is the record of which release reached
 * {@code main} and when, and it is the only place that fact lives. Deleting it would make "no row"
 * mean both "never released" and "long since merged".
 *
 * <p>{@link Uncaused}, and the reason is the same one {@code RepositoryName} gives. The insert
 * happens on the release worker — off every request thread, after a door call — where no {@code
 * CausationScope} stands, so a stamp would record null forever. The request that caused it is one
 * column away ({@link #releaseRequestId}) and <em>is</em> a caused row.
 */
@Entity
@Table(name = "released_tag_pending_merge")
@Uncaused
public class ReleasedTagPendingMerge extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The tag's own name — the calver the release answered with, never a ref. */
  @Column(name = "tag_name", nullable = false)
  public String tagName;

  /** What the tag points at: the merged sha that was released. */
  @Column(name = "released_sha", nullable = false)
  public String releasedSha;

  /** Which request produced it, where one did. Null for a tag recorded by any other path. */
  @Column(name = "release_request_id")
  public String releaseRequestId;

  @Column(name = "released_at", nullable = false)
  public Instant releasedAt;

  /** Null while the tag is still in flight; stamped when the post-deployment merge lands it. */
  @Column(name = "merged_at")
  public Instant mergedAt;
}
