package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.AuditService;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.control.FeatureService;
import eu.wohlben.qits.epics.dto.AuditEntryDto;
import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.dto.FeatureDto;
import eu.wohlben.qits.epics.mapper.AuditEntryMapper;
import eu.wohlben.qits.epics.mapper.EpicMapper;
import eu.wohlben.qits.epics.mapper.FeatureMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** A single epic (the spine), its feature collection, and its audit subtree. */
@Path("/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class EpicController {

  @Inject EpicService epicService;

  @Inject FeatureService featureService;

  @Inject AuditService auditService;

  @Inject EpicMapper epicMapper;

  @Inject FeatureMapper featureMapper;

  @Inject AuditEntryMapper auditEntryMapper;

  @Inject SecurityIdentity identity;

  @Inject EpicChangeHints hints;

  // --- Epic ---

  public record GetEpicRequest() {
    public record Response(EpicDto epic) {}
  }

  @GET
  @Path("/{id}")
  public GetEpicRequest.Response get(@PathParam("id") String id) {
    return new GetEpicRequest.Response(epicMapper.toDto(epicService.get(id)));
  }

  public record UpdateEpicRequest(@NotBlank String title, String description) {
    public record Response(EpicDto epic) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateEpicRequest.Response update(
      @PathParam("id") String id, @Valid UpdateEpicRequest request) {
    var epic =
        epicService.update(
            id, request.title(), request.description(), EpicsPrincipal.changedBy(identity));
    hints.fire(epic.projectId);
    return new UpdateEpicRequest.Response(epicMapper.toDto(epic));
  }

  /**
   * A lifecycle move. {@code target} is the status name — {@code IMPLEMENTATION} (the scope
   * freeze), {@code IMPLEMENTED} (shipped: stamps every feature and task still unimplemented),
   * {@code SUPERSEDED} or {@code ABANDONED}. A move the lifecycle does not allow, and a target
   * naming no status, both answer 409 with a message.
   */
  public record TransitionEpicRequest(String target) {
    /** The epic in its new status, plus the successor draft a supersede spawned (null otherwise). */
    public record Response(EpicDto epic, EpicDto successor) {}
  }

  @POST
  @Path("/{id}/transition")
  public TransitionEpicRequest.Response transition(
      @PathParam("id") String id, @Valid TransitionEpicRequest request) {
    var result =
        epicService.transition(id, request.target(), EpicsPrincipal.changedBy(identity));
    // A supersede spawns a second epic in the same project, so one hint still covers both rows.
    hints.fire(result.epic().projectId);
    return new TransitionEpicRequest.Response(
        epicMapper.toDto(result.epic()),
        result.successor() == null ? null : epicMapper.toDto(result.successor()));
  }

  public record DeleteEpicRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteEpicRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfEpic(id);
    epicService.delete(id, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteEpicRequest.Response(true);
  }

  // --- Features under an epic ---

  public record ListFeaturesRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(FeatureDto feature) {}
    }
  }

  @GET
  @Path("/{epicId}/features")
  public ListFeaturesRequest.Response listFeatures(@PathParam("epicId") String epicId) {
    epicService.get(epicId); // 404 if the epic does not exist
    var entries =
        featureService.listByEpic(epicId).stream()
            .map(f -> new ListFeaturesRequest.Response.Entry(featureMapper.toDto(f)))
            .toList();
    return new ListFeaturesRequest.Response(entries);
  }

  public record CreateFeatureRequest(
      @NotBlank String title, String description, String dependsOnFeatureId) {
    public record Response(FeatureDto feature) {}
  }

  @POST
  @Path("/{epicId}/features")
  public CreateFeatureRequest.Response createFeature(
      @PathParam("epicId") String epicId, @Valid CreateFeatureRequest request) {
    var feature =
        featureService.create(
            epicId,
            request.title(),
            request.description(),
            request.dependsOnFeatureId(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfEpic(epicId));
    return new CreateFeatureRequest.Response(featureMapper.toDto(feature));
  }

  // --- Audit subtree ---

  public record EpicAuditRequest() {
    public record Response(List<AuditEntryDto> entries) {}
  }

  /**
   * Full change history for the epic subtree, newest first. Queried by the {@code epicId} column
   * stamped on every audit row, so it includes rows for features/tasks already deleted and remains
   * readable after the epic itself is deleted (the audit log is the git replacement — it must
   * outlive the rows). Deliberately does NOT require the epic to still exist.
   */
  @GET
  @Path("/{id}/audit")
  public EpicAuditRequest.Response audit(@PathParam("id") String id) {
    var entries = auditService.listForEpic(id).stream().map(auditEntryMapper::toDto).toList();
    return new EpicAuditRequest.Response(entries);
  }
}
