package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.TaskService;
import eu.wohlben.qits.epics.dto.TaskDto;
import eu.wohlben.qits.epics.mapper.TaskMapper;
import eu.wohlben.qits.projects.validation.NotBlankIfPresent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;

/** A single task. */
@Path("/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TaskController {

  @Inject TaskService taskService;

  @Inject TaskMapper taskMapper;

  @Inject SecurityIdentity identity;

  @Inject EpicChangeHints hints;

  public record GetTaskRequest() {
    public record Response(TaskDto task) {}
  }

  @GET
  @Path("/{id}")
  public GetTaskRequest.Response get(@PathParam("id") String id) {
    return new GetTaskRequest.Response(taskMapper.toDto(taskService.get(id)));
  }

  /**
   * Partial update: a null {@code title}/{@code description} leaves it unchanged. The nullable
   * dependency and completion marker change only when their {@code clear*} flag is true (→ cleared)
   * or a non-null value is supplied (→ set) — so a title-only edit can't silently un-complete a
   * task or drop its dependency.
   */
  public record UpdateTaskRequest(
      @NotBlankIfPresent String title,
      String description,
      String dependsOnTaskId,
      boolean clearDependsOn,
      Instant implementedAt,
      boolean clearImplementedAt) {
    public record Response(TaskDto task) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateTaskRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTaskRequest request) {
    var task =
        taskService.update(
            id,
            request.title(),
            request.description(),
            request.dependsOnTaskId(),
            request.clearDependsOn(),
            request.implementedAt(),
            request.clearImplementedAt(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfFeature(task.featureId));
    return new UpdateTaskRequest.Response(taskMapper.toDto(task));
  }

  public record DeleteTaskRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTaskRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfTask(id);
    taskService.delete(id, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteTaskRequest.Response(true);
  }
}
