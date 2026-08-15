package eu.wohlben.qits.projects.api;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The single Server-Sent-Events channel for one project's page. Emits payload-free
 * <em>invalidation hints</em> — a topic name per frame ({@code epics}, {@code agent-activity}) —
 * which the frontend maps to a query invalidation, so data keeps flowing through the unchanged REST
 * endpoints. That is what lets a browser watch a refinement agent draft an epic live without any
 * poll. A ~25s {@code ping} heartbeat keeps idle connections alive through the dev proxies; {@code
 * EventSource} reconnects on its own and the frontend re-syncs on reconnect, so there is no
 * replay/{@code Last-Event-ID} protocol.
 */
@Path("/projects/{projectId}/events")
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class ProjectEventsController {

  @Inject ProjectEventBroadcaster broadcaster;

  /**
   * {@code @Blocking} is load-bearing: subscribing resolves the project id against the database,
   * and without it that read runs on the IO thread. Only the subscribe blocks — the returned
   * {@link Multi} streams as it would otherwise.
   */
  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Blocking
  public Multi<String> events(@PathParam("projectId") String projectId) {
    return broadcaster.withHeartbeat(broadcaster.subscribeToProject(projectId));
  }
}
