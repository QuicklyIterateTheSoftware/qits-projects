package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.WorkspacePromptAttachmentService;
import eu.wohlben.qits.domain.repository.control.WorkspacePromptDraftService;
import eu.wohlben.qits.domain.repository.dto.WorkspacePromptDraftDto;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptAttachment;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import eu.wohlben.qits.domain.repository.mapper.WorkspacePromptDraftMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;

/**
 * The workspace's persisted prompt draft — one row per workspace, autosaved as the operator
 * composes and rehydrated on load so a half-built prompt survives a refresh (and follows the
 * operator across devices). Pure host-side data: no container is materialized, so these work on a
 * {@code STOPPED} workspace. A GET on a workspace that has never saved a draft is a 404. See {@code
 * WorkspacePromptDraftService} for the guardrails.
 */
@Path("/repositories/{repoId}/workspaces/{workspaceId}/prompt-draft")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspacePromptDraftController {

  @Inject WorkspacePromptDraftService promptDraftService;

  @Inject WorkspacePromptDraftMapper promptDraftMapper;

  @Inject WorkspacePromptAttachmentService attachmentService;

  @GET
  public WorkspacePromptDraftDto get(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return promptDraftMapper.toDto(promptDraftService.getDraft(repoId, workspaceId));
  }

  public static record SaveDraftRequest(@NotNull String content, String serializedPrompt) {
    public record Response(Instant updatedAt) {}
  }

  @PUT
  public SaveDraftRequest.Response put(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      // @NotNull on the parameter (not just the record's `content` field) so an absent request body
      // is a 400, not a NullPointerException → 500 when we read request.content().
      @Valid @NotNull SaveDraftRequest request) {
    WorkspacePromptDraft draft =
        promptDraftService.saveDraft(
            repoId, workspaceId, request.content(), request.serializedPrompt());
    return new SaveDraftRequest.Response(draft.updatedAt);
  }

  @DELETE
  public void delete(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    promptDraftService.deleteDraft(repoId, workspaceId);
  }

  public static record AddAttachmentRequest(
      @NotNull String mimeType,
      @NotNull String label,
      @NotNull String source,
      @NotNull String dataBase64) {
    public record Response(String id) {}
  }

  @POST
  @Path("attachments")
  public AddAttachmentRequest.Response addAttachment(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @Valid @NotNull AddAttachmentRequest request) {
    WorkspacePromptAttachment attachment =
        attachmentService.addAttachment(
            repoId,
            workspaceId,
            request.mimeType(),
            request.label(),
            request.source(),
            request.dataBase64());
    return new AddAttachmentRequest.Response(attachment.id);
  }

  @DELETE
  @Path("attachments/{attachmentId}")
  public void deleteAttachment(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @PathParam("attachmentId") String attachmentId) {
    attachmentService.deleteAttachment(repoId, workspaceId, attachmentId);
  }
}
