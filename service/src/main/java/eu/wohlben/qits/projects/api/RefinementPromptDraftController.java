package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.RefinementPromptDraft;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.refinementhost.RefinementPromptDrafts;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;

/**
 * The refinement's prompt draft — the same three verbs and the same 404-means-none contract the
 * workspaces draft controller taught the SPA, on this service's row.
 */
@Path("/refinements/{id}/prompt-draft")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class RefinementPromptDraftController {

  @Inject RefinementService refinements;

  @Inject RefinementPromptDrafts drafts;

  public record SaveRequest(@NotNull String content, String serializedPrompt) {}

  public record DraftDto(
      String content,
      String serializedPrompt,
      Long promptVersion,
      Instant lastRunAt,
      Long lastRunPromptVersion,
      String lastRunCommandId,
      Instant updatedAt) {}

  public record DraftResponse(DraftDto draft) {}

  /** 404 when no draft has been saved — deliberately, so "none" and "empty" stay distinct. */
  @GET
  public DraftResponse get(@PathParam("id") long id) {
    refinements.get(id);
    RefinementPromptDraft draft =
        drafts.find(id).orElseThrow(() -> new NotFoundException("No draft"));
    return new DraftResponse(dto(draft));
  }

  @PUT
  public DraftResponse save(@PathParam("id") long id, SaveRequest request) {
    refinements.get(id);
    return new DraftResponse(dto(drafts.save(id, request.content(), request.serializedPrompt())));
  }

  /** 204 always — deleting an absent draft is the asked-for state. */
  @DELETE
  public void delete(@PathParam("id") long id) {
    refinements.get(id);
    drafts.delete(id);
  }

  private static DraftDto dto(RefinementPromptDraft draft) {
    return new DraftDto(
        draft.content,
        draft.serializedPrompt,
        draft.promptVersion,
        draft.lastRunAt,
        draft.lastRunPromptVersion,
        draft.lastRunCommandId,
        draft.updatedAt);
  }
}
