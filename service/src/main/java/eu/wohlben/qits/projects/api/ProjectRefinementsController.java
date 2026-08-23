package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.refinementhost.RefinementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A project's refinements, as a list — the find-without-create read (the refining page matches its
 * epic here before offering to start one) and the activity bar's "who needs me next" row.
 *
 * <p><b>The light projection, deliberately.</b> Each row carries its live halves (runtime status,
 * agent activity, daemon presence) but no git drift — ahead/behind/conflicts cost a mirror refresh
 * and a merge preview per row, which a list that redraws on every agent-activity hint must not pay.
 * The single-row read is where the drift lives.
 */
@Path("/projects/{projectId}/refinements")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class ProjectRefinementsController {

  @Inject RefinementService refinements;

  public record ListResponse(List<RefinementDto> refinements) {}

  @GET
  public ListResponse list(@PathParam("projectId") String projectId) {
    return new ListResponse(
        refinements.listByProject(projectId).stream().map(RefinementDto::of).toList());
  }
}
