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
 * One <b>named</b> participant of a release request — a branch somebody put on it.
 *
 * <p>A release request is an octopus merge of N sources now, and they come from two places. This
 * table holds the ones a <b>caller chose</b>: {@code main} (implied by every create) plus each
 * branch named at creation or added later. The other kind — the repository's released tags not yet
 * merged to {@code main} — is <b>derived</b> from {@link ReleasedTagPendingMerge} and deliberately
 * has no row here: it is a fact about the repository at this moment, not a choice about this
 * request, and a tag that reached {@code main} must leave every request's source set at once. A row
 * here would have to be deleted from N requests to do that, and would be wrong in between.
 *
 * <p><b>{@link #name} is the branch's own name, never a ref.</b> {@code refs/heads/} is the git
 * host's spelling and is applied where the merge is called, so a row stays readable by a person and
 * one place decides the prefix.
 *
 * <p>A {@link CausedRow}: every insert happens on the request thread that asked for it — the create
 * and the add-source route — so the stamp records what asked. The re-merge never writes one.
 */
@Entity
@Table(name = "release_request_source")
@EntityListeners(CausationStamp.class)
public class ReleaseRequestSource extends PanacheEntityBase implements CausedRow {

  /**
   * What kind of ref a source is. {@code BRANCH} is the only kind ever stored; {@code RELEASED_TAG}
   * exists because a <b>read</b> reports both — the implicit sources are answered as this kind
   * without ever being persisted, so the API has one vocabulary rather than two.
   */
  public enum Kind {
    BRANCH,
    RELEASED_TAG
  }

  @Id public String id;

  /** The request this participates in. A plain column: the parent is loaded by id, never joined. */
  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public Kind kind;

  /** The branch name as a person spells it — {@code main}, {@code feature/checkout}. */
  @Column(nullable = false, length = 512)
  public String name;

  @Column(name = "added_at", nullable = false)
  public Instant addedAt;

  /** Who put it on the request; null for the implied {@code main} of a machine-made create. */
  @Column(name = "added_by")
  public String addedBy;

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
