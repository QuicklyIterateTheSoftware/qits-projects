package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The release requests: the asynchronous ask that replaces calling the release door blind. A
 * request is created PENDING, the build gate settles it off the commit ledger, and the execution
 * arm performs the release once it is READY — see {@code control/ReleaseRequests} for the state
 * machine, which this controller only fronts.
 *
 * <p><b>Two callers, two roles, on every route</b>: a person driving a release from a browser, and
 * the machine peers the door split brings (qits-workspaces creating requests on behalf of its
 * callers, the train's scripts). A method-level {@code @RolesAllowed} <b>replaces</b> the
 * class-level one, so both are spelled at the class and no method narrows it.
 */
@Path("/repositories/{repoId}/release-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class ReleaseRequestController {

  @Inject ReleaseRequests releaseRequests;

  @Inject SecurityIdentity identity;

  /**
   * @param branch what to release
   * @param commitSha the branch head the caller means — a request is about a sha, so the caller
   *     states which one rather than this service guessing at a head that may move mid-flight
   * @param summary the release door's summary line
   * @param requester whom the caller acts for — attribution as data, for the machine peers whose
   *     bearer names the service rather than the person at the door. Blank falls back to the
   *     caller's own identity. Every caller here already holds admin or system, so stating an
   *     actor is not an escalation.
   */
  public static record CreateReleaseRequest(
      @NotBlank String branch,
      @NotBlank String commitSha,
      @NotBlank String summary,
      String requester) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Operation(
      summary = "Ask for a branch to be released once its builds are green",
      description =
          "Creates (or converges on) the open release request for the branch. The request is about"
              + " the named sha: gates evaluate that commit, and a request for a different sha"
              + " withdraws the open one. Poll the request until it is RELEASED, REJECTED or"
              + " FAILED; detail says why for the latter two.")
  public CreateReleaseRequest.Response create(
      @PathParam("repoId") String repoId, CreateReleaseRequest body) {
    String requester =
        body.requester() != null && !body.requester().isBlank()
            ? body.requester().trim()
            : (identity.isAnonymous() ? null : identity.getPrincipal().getName());
    return new CreateReleaseRequest.Response(
        releaseRequests.request(repoId, body.branch(), body.commitSha(), body.summary(), requester));
  }

  /** @param reason optional sentence recorded on the request; a default names the caller. */
  public static record WithdrawReleaseRequest(String reason) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/withdraw")
  @Operation(
      summary = "Withdraw an open release request",
      description =
          "The ask is moot — nothing should land this branch. WITHDRAWN is terminal and frees the"
              + " branch: the next release ask mints a fresh request. A request already RELEASED or"
              + " WITHDRAWN answers 409. A deleted branch withdraws its open request"
              + " automatically; this route is the operator's spelling for every other reason.")
  public WithdrawReleaseRequest.Response withdraw(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      WithdrawReleaseRequest body) {
    String actor = identity.isAnonymous() ? null : identity.getPrincipal().getName();
    return new WithdrawReleaseRequest.Response(
        releaseRequests.withdraw(requestId, body == null ? null : body.reason(), actor));
  }

  public static record ListReleaseRequests() {
    public record Response(List<ReleaseRequestDto> requests) {}
  }

  @GET
  public ListReleaseRequests.Response list(@PathParam("repoId") String repoId) {
    return new ListReleaseRequests.Response(releaseRequests.listByRepo(repoId));
  }

  public static record GetReleaseRequest() {
    public record Response(ReleaseRequestDto request) {}
  }

  @GET
  @Path("/{requestId}")
  public GetReleaseRequest.Response get(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return new GetReleaseRequest.Response(releaseRequests.get(requestId));
  }
}
