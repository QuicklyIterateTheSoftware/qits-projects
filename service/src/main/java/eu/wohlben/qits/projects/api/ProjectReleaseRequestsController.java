package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * A project's release requests, across every repository it owns — the one read that shows what is
 * waiting to be released without opening each repository in turn.
 *
 * <p><b>Why a route of its own rather than a query on the repository collection.</b> The
 * repository-scoped controller answers about one repository and is the machine peers' surface (the
 * door, the train). This is a person's overview: the scope is the project, the default is the
 * pending work — plus the handful that has just landed — rather than the whole history, and the rows
 * carry the repository's name because nothing else on the page says which repository a branch
 * belongs to.
 *
 * <p>The roles match {@code ReleaseRequestController}'s deliberately: reading a request tells you
 * what is being released and by whom, so the same two callers see it and no wider audience does.
 *
 * <p>An unknown project is a 404 rather than an empty list, the same reasoning the unknown-state
 * 400 in {@code ReleaseRequests} follows: on a page whose whole message is "nothing is pending
 * here", a mistyped scope must not be able to say it.
 */
@Path("/projects/{projectId}/release-requests")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class ProjectReleaseRequestsController {

  @Inject ReleaseRequests releaseRequests;

  @Inject ProjectService projects;

  public record ListProjectReleaseRequests() {
    public record Response(List<ReleaseRequestDto> requests) {}
  }

  /**
   * @param state which requests to answer: omitted means the open ones (PENDING, READY, FAILED,
   *     REJECTED, CONFLICTED — everything that can still move) plus the last 10 released, {@code
   *     all} means every state, and a state's own name narrows to it. A word naming no state is a
   *     400.
   */
  @GET
  @Operation(
      summary = "The project's release requests, across all of its repositories",
      description =
          "Most recently moved first. With no state the answer is the open requests — the work"
              + " still waiting on somebody — plus the last 10 released, so that a release does not"
              + " leave the page the moment it lands. Pass state=all for the whole history, or a"
              + " state name (PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, WITHDRAWN) to"
              + " narrow to one.")
  public ListProjectReleaseRequests.Response list(
      @PathParam("projectId") String projectId, @QueryParam("state") String state) {
    projects.get(projectId); // 404 if the project does not exist
    return new ListProjectReleaseRequests.Response(
        releaseRequests.listByProject(projectId, state));
  }
}
