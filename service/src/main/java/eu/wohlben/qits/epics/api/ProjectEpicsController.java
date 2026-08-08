package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.mapper.EpicMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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

/**
 * Epics collection under a project. {@code projectId} is validated against {@code domain} here (the
 * epics module has no dependency on {@code domain}) so a bad project yields a clean 404.
 */
@Path("/projects/{projectId}/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectEpicsController {

  @Inject EpicService epicService;

  @Inject EpicMapper epicMapper;

  @Inject ProjectService projectService;

  @Inject SecurityIdentity identity;

  public record ListEpicsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(EpicDto epic) {}
    }
  }

  /**
   * The project's epics, oldest first, optionally narrowed to one phase. {@code status} is the
   * status name; a value naming none is a 400, so a typo in the filter does not read as "no epics".
   */
  @GET
  public ListEpicsRequest.Response list(
      @PathParam("projectId") String projectId, @QueryParam("status") String status) {
    projectService.get(projectId); // 404 if the project does not exist
    var entries =
        epicService.listByProject(projectId, status).stream()
            .map(e -> new ListEpicsRequest.Response.Entry(epicMapper.toDto(e)))
            .toList();
    return new ListEpicsRequest.Response(entries);
  }

  public record CreateEpicRequest(@NotBlank String title, String description) {
    public record Response(EpicDto epic) {}
  }

  @POST
  public CreateEpicRequest.Response create(
      @PathParam("projectId") String projectId, @Valid CreateEpicRequest request) {
    projectService.get(projectId); // 404 if the project does not exist
    var epic =
        epicService.create(
            projectId, request.title(), request.description(), EpicsPrincipal.changedBy(identity));
    return new CreateEpicRequest.Response(epicMapper.toDto(epic));
  }
}
