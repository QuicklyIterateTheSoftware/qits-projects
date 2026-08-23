package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.refinementhost.RefinementDesigns;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import io.quarkus.security.identity.SecurityIdentity;
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
import java.util.List;

/**
 * The refinement's frozen HTML designs — the Design tab's surface. A design is one self-contained
 * document with inline styles: either what the person sees (ACTIVE) or an agent's proposed revision
 * (PROPOSED), which only a person resolves.
 *
 * <p>The document travels as a JSON field, and the list leaves it out: a design is bytes measured
 * in megabytes and a listing is drawn from titles and sizes.
 *
 * <p>There is deliberately NO {@code /content} route serving {@code text/html}: agent-authored HTML
 * served same-origin would be an XSS door, so the SPA renders each design in a sandboxed iframe
 * with scripts off.
 */
@Path("/refinements/{id}/designs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class RefinementDesignController {

  /** What a write with no forwarded identity records as its author. */
  private static final String UNKNOWN = "unknown";

  @Inject RefinementService refinements;

  @Inject RefinementDesigns designs;

  @Inject SecurityIdentity identity;

  public record NewDesign(
      @NotBlank String title, @NotBlank String html, String sourceRoute, boolean truncated) {}

  public record RenameDesign(@NotBlank String title) {}

  public record ResolveDesign(@NotBlank String mode) {}

  public record DesignDto(
      String id,
      String title,
      String status,
      String basedOnDesignId,
      String note,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      String html) {}

  public record ListResponse(List<DesignDto> designs) {}

  /** Oldest first, without the documents. Empty list, never a 404. */
  @GET
  public ListResponse list(@PathParam("id") long id) {
    refinements.get(id);
    return new ListResponse(designs.list(id).stream().map(row -> dto(row, false)).toList());
  }

  /** 201 with the row sans html — the caller just sent it. */
  @POST
  public Response add(@PathParam("id") long id, NewDesign request) {
    refinements.get(id);
    RefinementDesign row =
        designs.add(
            id,
            request.title(),
            request.html(),
            request.sourceRoute(),
            request.truncated(),
            createdBy());
    return Response.status(Response.Status.CREATED).entity(dto(row, false)).build();
  }

  /** One design with its whole document — what the sandboxed iframe is fed. */
  @GET
  @Path("/{designId}")
  public DesignDto get(@PathParam("id") long id, @PathParam("designId") String designId) {
    refinements.get(id);
    return dto(designs.get(id, designId), true);
  }

  @PUT
  @Path("/{designId}")
  public DesignDto rename(
      @PathParam("id") long id, @PathParam("designId") String designId, RenameDesign request) {
    refinements.get(id);
    return dto(designs.rename(id, designId, request.title()), false);
  }

  /** The person's decision on a proposal: REPLACE the original, or KEEP it as its own design. */
  @POST
  @Path("/{designId}/resolve")
  public DesignDto resolve(
      @PathParam("id") long id, @PathParam("designId") String designId, ResolveDesign request) {
    refinements.get(id);
    return dto(designs.resolve(id, designId, modeOf(request.mode())), false);
  }

  @DELETE
  @Path("/{designId}")
  public void delete(@PathParam("id") long id, @PathParam("designId") String designId) {
    refinements.get(id);
    designs.delete(id, designId);
  }

  private static RefinementDesigns.Resolution modeOf(String mode) {
    try {
      return RefinementDesigns.Resolution.valueOf(mode);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new DomainException(400, "The resolution must be REPLACE or KEEP.");
    }
  }

  /** The forwarded user, else the marker — a design row always names an author. */
  private String createdBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return UNKNOWN;
    }
    return identity.getPrincipal().getName();
  }

  private static DesignDto dto(RefinementDesign row, boolean withHtml) {
    return new DesignDto(
        row.id,
        row.title,
        row.status.name(),
        row.basedOnDesignId,
        row.note,
        row.sourceRoute,
        row.htmlBytes,
        row.truncated,
        row.createdBy,
        row.createdAt,
        row.updatedAt,
        withHtml ? row.html : null);
  }
}
