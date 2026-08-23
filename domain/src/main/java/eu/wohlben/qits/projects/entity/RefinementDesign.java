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
 * One frozen HTML design kept with a refinement — a self-contained document of a single page, with
 * its styles inline, that the refining route's Design tab shows beside the epic being drafted. A
 * row is either what the person is looking at ({@code ACTIVE}) or an agent's proposed revision of
 * it ({@code PROPOSED}), and only a person turns the second into the first.
 *
 * <p><b>It is never served as a page.</b> There is no content route and there must not be one: the
 * HTML is agent-authored, and same-origin delivery would make every proposal an XSS door into the
 * platform's own session. The SPA renders it in a sandboxed iframe with scripts off, which is why
 * the bytes travel as a JSON field and nothing else.
 *
 * <p>Cascades with the refinement, like the prompt draft and attachments beside it: a design has no
 * life of its own once the refinement it belongs to is discarded.
 *
 * <p><b>A {@link CausedRow}.</b> A proposal is minted on the request thread that carried the
 * agent's tool call, so the stamp records what asked for it — the one trace tying a design back to
 * the turn that wrote it.
 */
@Entity
@Table(name = "refinement_design")
@EntityListeners(CausationStamp.class)
public class RefinementDesign extends PanacheEntityBase implements CausedRow {

  /** Whether the design is the one on show or a revision still waiting for a decision. */
  public enum Status {
    /** What the person sees in the Design tab. */
    ACTIVE,
    /** An agent's proposal; the person replaces, keeps or discards it. */
    PROPOSED
  }

  /** A string UUID minted by the control class. */
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "refinement_id_fk", nullable = false)
  public Long refinementId;

  @Column(name = "title", nullable = false)
  public String title;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  public Status status;

  /** The ACTIVE design this proposal revises, or null — a proposal may also stand on its own. */
  @Column(name = "based_on_design_id")
  public String basedOnDesignId;

  /** The agent's rationale on a proposal. Null on an ACTIVE row. */
  @Column(name = "note", columnDefinition = "text")
  public String note;

  /** The route in the framed application this design was captured from, if one was known. */
  @Column(name = "source_route")
  public String sourceRoute;

  /** The whole document, styles inline. */
  @Column(name = "html", nullable = false, columnDefinition = "text")
  public String html;

  /** UTF-8 length of {@link #html} — what a list shows without carrying the document. */
  @Column(name = "html_bytes", nullable = false)
  public int htmlBytes;

  /** The capture was cut short, so the document is a partial page. */
  @Column(name = "truncated", nullable = false)
  public boolean truncated;

  @Column(name = "created_by", nullable = false)
  public String createdBy;

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
