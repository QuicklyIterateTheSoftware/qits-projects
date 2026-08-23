package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One image attached to a refinement's prompt — a sketch export or a paste — held host-side so the
 * epic document's embedded image URLs outlive the container. The projects twin of qits-workspaces'
 * {@code WorkspacePromptAttachment}.
 *
 * <p>The id is a string UUID minted here and embedded into content URLs (and from there into epic
 * markdown), so it must never be renumbered; a replace-in-place keeps it. {@code @Uncaused} like
 * the draft beside it.
 */
@Entity
@Table(name = "refinement_prompt_attachment")
@Uncaused
public class RefinementPromptAttachment extends PanacheEntityBase {

  /** Where an attached image came from — what the chip in the UI says. */
  public enum Source {
    SKETCH,
    PASTE
  }

  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "refinement_id_fk", nullable = false)
  public Long refinementId;

  /** Sniffed from the bytes, never taken from the upload's claim. */
  @Column(name = "mime_type", nullable = false)
  public String mimeType;

  @Column(name = "label", nullable = false)
  public String label;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false)
  public Source source;

  @Column(name = "bytes", nullable = false, columnDefinition = "bytea")
  public byte[] bytes;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
