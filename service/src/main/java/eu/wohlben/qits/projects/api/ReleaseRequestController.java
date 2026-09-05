package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ReleaseArtifacts;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseArtifactsDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestCommitsDto;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The release requests: the asynchronous ask that replaces calling the release door blind. A
 * request is an <b>octopus merge of N sources</b> — {@code main}, the branches somebody put on it,
 * and the repository's released tags not yet merged to {@code main} — folded onto {@code
 * release/<id>}; it is created PENDING, the build gate settles it off the commit ledger against the
 * merged sha, and the execution arm performs the release once it is READY. See {@code
 * control/ReleaseRequests} for the state machine, which this controller only fronts.
 *
 * <p><b>Named sources are caller-managed; implicit ones are not.</b> A create names one branch and
 * implies {@code main}; {@code POST …/{requestId}/sources} adds more. The released tags are derived
 * from what the repository has in flight, are reported in every read, and cannot be added or dropped
 * — an API that let a caller drop one would let somebody release a step backwards from what is
 * already shipping.
 *
 * <p><b>Two reads hang off a single request and neither is a column.</b> {@code …/commits} is the
 * fold's own range, read out of the repository's mirror, and {@code …/artifacts} is what the
 * released tag's tree declares was published. Both exist because "the release landed" is the
 * beginning of a person's question rather than the end of it, and both answer 200 with a sentence
 * where they cannot answer with a list — see their operations below.
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

  @Inject ReleaseArtifacts releaseArtifacts;

  @Inject SecurityIdentity identity;

  /**
   * @param branch the branch to release. {@code main} is <b>implied</b> and is never asked for — a
   *     release that does not contain what is already on main is not a release anybody wants — so a
   *     fresh request's named sources are the repository's default branch and this one. Naming the
   *     default branch itself makes a main-only request.
   * @param summary the release's summary line, which is also the fold's commit message
   * @param requester whom the caller acts for — attribution as data, for the machine peers whose
   *     bearer names the service rather than the person at the door. Blank falls back to the
   *     caller's own identity. Every caller here already holds admin or system, so stating an
   *     actor is not an escalation.
   */
  public static record CreateReleaseRequest(
      @NotBlank String branch, @NotBlank String summary, String requester) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Operation(
      summary = "Ask for a branch to be released once its builds are green",
      description =
          "Creates (or converges on) the open release request the branch participates in. The"
              + " request's sources are main plus the named branch, plus every released tag of the"
              + " repository not yet merged to main; they are folded onto release/<id> and it is"
              + " that MERGE the gates evaluate — mergedSha on the answer. A branch that already"
              + " participates in an open request answers that request rather than opening a"
              + " second. Poll until RELEASED, REJECTED, CONFLICTED or FAILED; detail says why, and"
              + " conflict says what to resolve.")
  public CreateReleaseRequest.Response create(
      @PathParam("repoId") String repoId, CreateReleaseRequest body) {
    return new CreateReleaseRequest.Response(
        releaseRequests.request(repoId, body.branch(), body.summary(), actorFor(body.requester())));
  }

  /** @param branch another branch to fold into this request. */
  public static record AddReleaseRequestSource(@NotBlank String branch, String requester) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/sources")
  @Operation(
      summary = "Add a branch to an open release request",
      description =
          "The request is re-folded with the new source and, if the fold produces a new commit, the"
              + " gates are re-armed onto it. Idempotent: a branch already on the request answers"
              + " the request unchanged. A RELEASED or WITHDRAWN request answers 409. Implicit tag"
              + " sources are not addable — they are derived from what the repository has in"
              + " flight.")
  public AddReleaseRequestSource.Response addSource(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      AddReleaseRequestSource body) {
    return new AddReleaseRequestSource.Response(
        releaseRequests.addSource(requestId, body.branch(), actorFor(body.requester())));
  }

  /** The stated actor, or the caller's own identity when none is stated. */
  private String actorFor(String stated) {
    if (stated != null && !stated.isBlank()) {
      return stated.trim();
    }
    return identity.isAnonymous() ? null : identity.getPrincipal().getName();
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

  /**
   * @param state which requests to answer, in the vocabulary the project-wide route uses: omitted
   *     means the open ones plus the last ten released, {@code all} means every state, and a state's
   *     own name narrows to it. A word naming no state is a 400.
   */
  @GET
  @Operation(
      summary = "This repository's release requests",
      description =
          "Newest first. With no state the answer is the open requests — everything that can still"
              + " move — plus the last 10 released, so that a release does not vanish off the page"
              + " the moment it lands. Pass state=all for the whole history (WITHDRAWN included), or"
              + " a state name (PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, WITHDRAWN)"
              + " to narrow to one.")
  public ListReleaseRequests.Response list(
      @PathParam("repoId") String repoId, @QueryParam("state") String state) {
    return new ListReleaseRequests.Response(releaseRequests.listByRepo(repoId, state));
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

  @GET
  @Path("/{requestId}/commits")
  @Operation(
      summary = "The commits this request's fold brought in",
      description =
          "The range mergedSha^1..mergedSha — the first parent of an octopus merge is the branch it"
              + " was folded onto, so what is left is exactly what the request's sources"
              + " contributed. It stays the same answer after the release reaches main. The version"
              + " bump is not in the list: the release commits the rewritten manifests ON TOP of the"
              + " fold. An empty list is never an error — detail says whether nothing has been"
              + " folded yet, the fold is no longer in the repository's history, or the fold"
              + " genuinely added nothing.")
  public ReleaseRequestCommitsDto commits(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return releaseRequests.mergedCommits(repoId, requestId);
  }

  @GET
  @Path("/{requestId}/artifacts")
  @Operation(
      summary = "What this release published, and whether anything deploys it",
      description =
          "Read out of the released tag's own tree: deployable is whether it declares"
              + " .config/qits/deployments.yml, and artifacts is what its release recipe declares"
              + " plus the userflow bundle its QA pipeline publishes (at the fold's sha, not at the"
              + " version). A request that has not released answers 200 with version null and a"
              + " detail saying so; a git host that cannot be asked and a recipe that will not parse"
              + " do the same. A repository that declares no recipe published nothing, and says so"
              + " with an empty list and no detail at all.")
  public ReleaseArtifactsDto artifacts(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return releaseArtifacts.of(repoId, requestId);
  }
}
