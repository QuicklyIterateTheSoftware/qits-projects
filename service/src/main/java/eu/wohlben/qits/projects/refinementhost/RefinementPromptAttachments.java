package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.RefinementPromptAttachment;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.RefinementPromptAttachmentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The refinement's prompt images — sketch exports and pastes, stored host-side so the epic
 * document's embedded content URLs outlive the container. Bytes are sniffed, never believed:
 * only PNG and JPEG magic get in, and the stored mime type is the sniffed one.
 */
@ApplicationScoped
public class RefinementPromptAttachments {

  @Inject RefinementPromptAttachmentRepository store;

  @Inject RefinementChangePublisher changes;

  @ConfigProperty(
      name = "qits.projects.refinement-prompt-attachment-max-bytes",
      defaultValue = "2097152")
  long maxBytes;

  /** Oldest first — the order the panel renders. */
  public List<RefinementPromptAttachment> list(long refinementId) {
    return QuarkusTransaction.requiringNew().call(() -> store.listByRefinement(refinementId));
  }

  public RefinementPromptAttachment get(long refinementId, String attachmentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByRefinementAndId(refinementId, attachmentId))
        .orElseThrow(() -> new NotFoundException("No such attachment"));
  }

  /** Add an image. The id is minted here and never renumbered — content URLs embed it. */
  public RefinementPromptAttachment add(
      long refinementId, String label, String source, String dataBase64) {
    byte[] bytes = decode(dataBase64);
    String mimeType = sniff(bytes);
    RefinementPromptAttachment row = new RefinementPromptAttachment();
    row.id = UUID.randomUUID().toString();
    row.refinementId = refinementId;
    row.mimeType = mimeType;
    row.label = label;
    row.source = sourceOf(source);
    row.bytes = bytes;
    row.createdAt = Instant.now();
    QuarkusTransaction.requiringNew().run(() -> store.persist(row));
    changes.fire(refinementId, RefinementChangeHint.Topic.PROMPT_ATTACHMENTS);
    return row;
  }

  /** Replace bytes in place, keeping the row id so document image URLs survive. */
  public RefinementPromptAttachment replace(
      long refinementId, String attachmentId, String label, String source, String dataBase64) {
    byte[] bytes = decode(dataBase64);
    String mimeType = sniff(bytes);
    RefinementPromptAttachment updated =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  RefinementPromptAttachment row =
                      store
                          .findByRefinementAndId(refinementId, attachmentId)
                          .orElseThrow(() -> new NotFoundException("No such attachment"));
                  row.mimeType = mimeType;
                  row.label = label;
                  row.source = sourceOf(source);
                  row.bytes = bytes;
                  return row;
                });
    changes.fire(refinementId, RefinementChangeHint.Topic.PROMPT_ATTACHMENTS);
    return updated;
  }

  public void delete(long refinementId, String attachmentId) {
    boolean removed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    store
                        .findByRefinementAndId(refinementId, attachmentId)
                        .map(
                            row -> {
                              store.delete(row);
                              return true;
                            })
                        .orElse(false));
    if (!removed) {
      throw new NotFoundException("No such attachment");
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.PROMPT_ATTACHMENTS);
  }

  private byte[] decode(String dataBase64) {
    if (dataBase64 == null || dataBase64.isBlank()) {
      throw new DomainException(400, "The attachment carries no data.");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(dataBase64);
    } catch (IllegalArgumentException e) {
      throw new DomainException(400, "The attachment data is not valid base64.");
    }
    if (bytes.length > maxBytes) {
      throw new DomainException(413, "The image is larger than " + maxBytes + " bytes.");
    }
    return bytes;
  }

  /** PNG or JPEG by magic bytes, or a 400 — the upload's own claim is never consulted. */
  private static String sniff(byte[] bytes) {
    if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4E
        && bytes[3] == 0x47
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A) {
      return "image/png";
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    throw new DomainException(400, "Only PNG and JPEG images can be attached.");
  }

  private static RefinementPromptAttachment.Source sourceOf(String source) {
    try {
      return RefinementPromptAttachment.Source.valueOf(source);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new DomainException(400, "The attachment source must be SKETCH or PASTE.");
    }
  }
}
