package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The refinement lifecycle surface the refining route drives — the projects-side replacement for
 * the workspace verbs it used to call on qits-workspaces. Keyed by epic on the way in
 * (find-or-create is one idempotent POST; the adopt-existing dance the old create needed is the
 * server's ordinary path now) and by refinement row id everywhere else.
 *
 * <p>The ensure and recreate verbs answer a technical-process id to watch; the work itself runs
 * off the request thread, behind an image pull if it must.
 */
@Path("/refinements")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class RefinementController {

  @Inject RefinementService refinements;

  /** Find-or-create the refinement of an epic. */
  public record OpenRequest(@NotBlank String epicId) {}

  public record RefinementResponse(RefinementDto refinement) {}

  public record ProcessResponse(RefinementDto refinement, String technicalProcessId) {}

  public record ActiveProcessResponse(String technicalProcessId) {}

  public record DiscardResponse(boolean success) {}

  @POST
  public RefinementResponse open(OpenRequest request) {
    Refinement refinement = refinements.findOrCreate(request.epicId());
    return new RefinementResponse(RefinementDto.of(refinements.view(refinement)));
  }

  @GET
  @Path("/{id}")
  public RefinementResponse get(@PathParam("id") long id) {
    return new RefinementResponse(RefinementDto.of(refinements.view(refinements.get(id))));
  }

  @POST
  @Path("/{id}/ensure-container")
  public ProcessResponse ensure(@PathParam("id") long id) {
    String processId = refinements.ensureContainer(id);
    return new ProcessResponse(
        RefinementDto.of(refinements.view(refinements.get(id))), processId);
  }

  @POST
  @Path("/{id}/recreate-container")
  public ProcessResponse recreate(@PathParam("id") long id) {
    String processId = refinements.recreateContainer(id);
    return new ProcessResponse(
        RefinementDto.of(refinements.view(refinements.get(id))), processId);
  }

  @POST
  @Path("/{id}/stop-container")
  public RefinementResponse stop(@PathParam("id") long id) {
    return new RefinementResponse(RefinementDto.of(refinements.view(refinements.stopContainer(id))));
  }

  @GET
  @Path("/{id}/active-process")
  public ActiveProcessResponse activeProcess(@PathParam("id") long id) {
    refinements.get(id); // 404 an unknown row rather than answering a hopeful null
    return new ActiveProcessResponse(refinements.activeProcessId(id));
  }

  /**
   * Tear the refinement down: container, volume, credential, branch, row. The epic's ABANDONED
   * transition is the caller's separate call on the epics surface, exactly as it was against
   * qits-workspaces.
   */
  @POST
  @Path("/{id}/discard")
  public DiscardResponse discard(@PathParam("id") long id) {
    refinements.discard(id);
    return new DiscardResponse(true);
  }
}
