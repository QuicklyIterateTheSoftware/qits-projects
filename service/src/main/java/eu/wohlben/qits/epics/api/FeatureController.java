package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.control.FeatureService;
import eu.wohlben.qits.epics.control.TaskService;
import eu.wohlben.qits.epics.dto.FeatureDto;
import eu.wohlben.qits.epics.dto.TaskDto;
import eu.wohlben.qits.epics.mapper.FeatureMapper;
import eu.wohlben.qits.epics.mapper.TaskMapper;
import eu.wohlben.qits.projects.validation.NotBlankIfPresent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.BadRequestException;
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
import java.util.List;

/** A single feature and its task collection. */
@Path("/features")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FeatureController {

  @Inject FeatureService featureService;

  @Inject TaskService taskService;

  @Inject EpicService epicService;

  @Inject FeatureMapper featureMapper;

  @Inject TaskMapper taskMapper;

  @Inject RepositoryService repositoryService;

  @Inject SecurityIdentity identity;

  @Inject EpicChangeHints hints;

  // --- Feature ---

  public record GetFeatureRequest() {
    public record Response(FeatureDto feature) {}
  }

  @GET
  @Path("/{id}")
  public GetFeatureRequest.Response get(@PathParam("id") String id) {
    return new GetFeatureRequest.Response(featureMapper.toDto(featureService.get(id)));
  }

  /**
   * Partial update: a null {@code title}/{@code description} leaves it unchanged. The nullable
   * dependency and ship-date change only when their {@code clear*} flag is true (→ cleared) or a
   * non-null value is supplied (→ set) — so a title-only edit can't silently un-ship a feature or
   * drop its dependency.
   */
  public record UpdateFeatureRequest(
      @NotBlankIfPresent String title,
      String description,
      String dependsOnFeatureId,
      boolean clearDependsOn,
      Instant implementedOn,
      boolean clearImplementedOn) {
    public record Response(FeatureDto feature) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateFeatureRequest.Response update(
      @PathParam("id") String id, @Valid UpdateFeatureRequest request) {
    var feature =
        featureService.update(
            id,
            request.title(),
            request.description(),
            request.dependsOnFeatureId(),
            request.clearDependsOn(),
            request.implementedOn(),
            request.clearImplementedOn(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfEpic(feature.epicId));
    return new UpdateFeatureRequest.Response(featureMapper.toDto(feature));
  }

  public record DeleteFeatureRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteFeatureRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfFeature(id);
    featureService.delete(id, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteFeatureRequest.Response(true);
  }

  // --- Tasks under a feature ---

  public record ListTasksRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(TaskDto task) {}
    }
  }

  @GET
  @Path("/{featureId}/tasks")
  public ListTasksRequest.Response listTasks(@PathParam("featureId") String featureId) {
    featureService.get(featureId); // 404 if the feature does not exist
    var entries =
        taskService.listByFeature(featureId).stream()
            .map(t -> new ListTasksRequest.Response.Entry(taskMapper.toDto(t)))
            .toList();
    return new ListTasksRequest.Response(entries);
  }

  public record CreateTaskRequest(
      @NotBlank String repositoryId,
      @NotBlank String title,
      String description,
      String dependsOnTaskId) {
    public record Response(TaskDto task) {}
  }

  @POST
  @Path("/{featureId}/tasks")
  public CreateTaskRequest.Response createTask(
      @PathParam("featureId") String featureId, @Valid CreateTaskRequest request) {
    // Validate the repository exists (404) AND belongs to the feature's epic's project — a task
    // must
    // not bind a repository from an unrelated project.
    var feature = featureService.get(featureId);
    var epic = epicService.get(feature.epicId);
    Repository repo = repositoryService.get(request.repositoryId()); // 404 if absent
    if (repo.project == null || !epic.projectId.equals(repo.project.id)) {
      throw new BadRequestException(
          "Repository " + request.repositoryId() + " is not in this epic's project");
    }
    var task =
        taskService.create(
            featureId,
            request.repositoryId(),
            request.title(),
            request.description(),
            request.dependsOnTaskId(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(epic.projectId);
    return new CreateTaskRequest.Response(taskMapper.toDto(task));
  }
}
