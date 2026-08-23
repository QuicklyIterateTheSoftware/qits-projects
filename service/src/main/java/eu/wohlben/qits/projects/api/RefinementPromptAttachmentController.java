package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.RefinementPromptAttachment;
import eu.wohlben.qits.projects.refinementhost.RefinementPromptAttachments;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * The refinement's prompt attachments — sketch exports and pastes with browser-loadable content
 * URLs, the same surface shape the workspaces controller taught the SPA. The content URL
 * ({@code /projects/api/refinements/{id}/prompt-attachments/{attachmentId}/content}) is embedded
 * into epic markdown, so attachment ids are never renumbered and a replace keeps its row.
 */
@Path("/refinements/{id}/prompt-attachments")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class RefinementPromptAttachmentController {

  @Inject RefinementService refinements;

  @Inject RefinementPromptAttachments attachments;

  public record NewAttachment(
      String mimeType, @NotBlank String label, @NotBlank String source, @NotBlank String dataBase64) {}

  public record AttachmentDto(
      String id,
      String mimeType,
      String label,
      String source,
      Instant createdAt,
      String dataBase64) {}

  public record ListResponse(List<AttachmentDto> attachments) {}

  /** Oldest first, bytes included — one read paints the panel. Empty list, never a 404. */
  @GET
  public ListResponse list(@PathParam("id") long id) {
    refinements.get(id);
    return new ListResponse(
        attachments.list(id).stream().map(row -> dto(row, true)).toList());
  }

  /** 201 with the row sans bytes — the caller just sent them. */
  @POST
  public Response add(@PathParam("id") long id, NewAttachment request) {
    refinements.get(id);
    RefinementPromptAttachment row =
        attachments.add(id, request.label(), request.source(), request.dataBase64());
    return Response.status(Response.Status.CREATED).entity(dto(row, false)).build();
  }

  @PUT
  @Path("/{attachmentId}")
  public AttachmentDto replace(
      @PathParam("id") long id,
      @PathParam("attachmentId") String attachmentId,
      NewAttachment request) {
    refinements.get(id);
    return dto(
        attachments.replace(id, attachmentId, request.label(), request.source(), request.dataBase64()),
        false);
  }

  /** The raw image, browser-loadable — what the epic document's embedded URLs point at. */
  @GET
  @Path("/{attachmentId}/content")
  @Produces({"image/png", "image/jpeg"})
  public Response content(@PathParam("id") long id, @PathParam("attachmentId") String attachmentId) {
    refinements.get(id);
    RefinementPromptAttachment row = attachments.get(id, attachmentId);
    return Response.ok(row.bytes, row.mimeType).header("Cache-Control", "no-cache").build();
  }

  @DELETE
  @Path("/{attachmentId}")
  public void delete(@PathParam("id") long id, @PathParam("attachmentId") String attachmentId) {
    refinements.get(id);
    attachments.delete(id, attachmentId);
  }

  private static AttachmentDto dto(RefinementPromptAttachment row, boolean withBytes) {
    return new AttachmentDto(
        row.id,
        row.mimeType,
        row.label,
        row.source.name(),
        row.createdAt,
        withBytes ? Base64.getEncoder().encodeToString(row.bytes) : null);
  }
}
