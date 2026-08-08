package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.agenthost.AgentContainerState;
import eu.wohlben.qits.projects.agenthost.AgentContainers;
import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The per-project agent container's lifecycle: ensure it is up, stop it, read what it is doing.
 *
 * <p>Three routes and one response shape, deliberately. The panel that drives this has exactly one
 * question — "can I talk to the agent yet?" — and answering it the same way from all three keeps
 * the client's state machine to one branch: it renders {@code container.runtimeStatus} and, when
 * that is {@code RUNNING}, opens its terminal through {@code /projects/container/{projectId}/…}.
 *
 * <p>The two halves of the answer are independent on purpose. {@code runtimeStatus} is docker's
 * view; {@code daemonConnected} is whether the process inside has dialled home. A container can be
 * {@code RUNNING} with no daemon connected for the seconds between {@code docker start} and the
 * first {@code Hello}, and the client shows "starting" rather than a broken terminal.
 *
 * <p>Everything below the surface is in {@code agenthost/}. This class only names the routes.
 */
// No @Consumes: all three routes are verbs on a resource and take no body, and declaring one would
// make a POST with no Content-Type a 415 rather than the action it plainly is.
@Path("/projects/{projectId}/agent-container")
@Produces(MediaType.APPLICATION_JSON)
public class AgentContainerController {

  @Inject AgentContainers agentContainers;

  /** The single response body all three routes answer with. */
  public record AgentContainerResponse(
      @Schema(description = "The project's agent container, as this service last observed it.")
          ContainerView container) {

    /**
     * @param runtimeStatus what the container is doing
     * @param daemonConnected whether the in-container daemon holds an open control socket
     * @param daemonVersion the daemon binary's release identity, or null when it has not said or
     *     was built without a version stamp
     */
    public record ContainerView(
        AgentRuntimeStatus runtimeStatus, boolean daemonConnected, String daemonVersion) {}

    static AgentContainerResponse of(AgentContainerState state) {
      return new AgentContainerResponse(
          new ContainerView(
              state.runtimeStatus(), state.daemonConnected(), state.daemonVersion()));
    }
  }

  /**
   * Bring the agent container up and answer what it is now: running containers are a no-op, stopped
   * ones are started in place (lossless — the checkout survives), and an absent one is provisioned.
   * Synchronous, so a 200 means docker has accepted the container; the daemon's own boot self-clone
   * runs after it and is reported through {@code daemonConnected} on a later read.
   */
  @POST
  @Path("/ensure")
  public AgentContainerResponse ensure(@PathParam("projectId") String projectId) {
    return AgentContainerResponse.of(agentContainers.ensure(projectId));
  }

  /**
   * Stop the agent container gracefully, keeping it and its checkout for a later lossless start.
   * Idempotent: a project with no container answers {@code ABSENT} rather than failing.
   */
  @POST
  @Path("/stop")
  public AgentContainerResponse stop(@PathParam("projectId") String projectId) {
    return AgentContainerResponse.of(agentContainers.stop(projectId));
  }

  /** What the agent container is doing, changing nothing. {@code ABSENT} when there is none. */
  @GET
  public AgentContainerResponse get(@PathParam("projectId") String projectId) {
    return AgentContainerResponse.of(agentContainers.status(projectId));
  }
}
