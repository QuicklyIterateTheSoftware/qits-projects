package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.refinementhost.RefinementEventBroadcaster;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * One refinement's hint channel: payload-free topic names ({@code agent-activity},
 * {@code git-status}, {@code process}, {@code files}, {@code commands}, {@code prompt-draft},
 * {@code prompt-attachments}) the frontend maps to query invalidations — the same wire contract the
 * workspaces channel taught it, on this service's own row. A ~25s {@code ping} keeps idle
 * connections alive; {@code EventSource} reconnects on its own and the frontend re-syncs on
 * connect, so there is no replay protocol.
 */
@Path("/refinements/{id}/events")
@RolesAllowed("qits:admin")
public class RefinementEventsController {

  @Inject RefinementEventBroadcaster broadcaster;

  @Inject RefinementService refinements;

  /** {@code @Blocking} because the subscribe 404s an unknown row, which is a database read. */
  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Blocking
  public Multi<String> events(@PathParam("id") long id) {
    refinements.get(id);
    return broadcaster.withHeartbeat(broadcaster.subscribe(id));
  }
}
