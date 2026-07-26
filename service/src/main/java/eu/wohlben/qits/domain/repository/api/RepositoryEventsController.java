package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.workspace.api.WorkspaceEventBroadcaster;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The Server-Sent-Events channel for one repository's detail route — the repository-scoped sibling
 * of {@code WorkspaceEventsController}. Emits payload-free <em>invalidation hints</em> (a topic
 * name per frame), which the frontend maps to a TanStack Query invalidation so the repository
 * detail route stays live without polling. Topics: {@code process} (a pull/sync began or completed
 * — refetch {@code .../active-process} to reattach or clear the concurrency guard), {@code
 * git-status} (a workspace's working tree flipped clean/dirty — refresh the branch-tree dirty
 * badge), and {@code agent-activity} (a coding agent's rollup flipped somewhere in this repo —
 * re-sort the agent-activity bar).
 *
 * <p>Reuses the existing {@link WorkspaceEventBroadcaster} keyed by {@code (repoId, null)}: that
 * key ({@code "repoId/null"}) can never collide with a workspace subscriber's {@code
 * "repoId/<workspaceId>"}, so a repository hint never misfires a workspace channel and vice versa.
 * A ~25s {@code ping} heartbeat keeps idle connections alive through the dev proxies; {@code
 * EventSource} reconnects automatically and the frontend re-syncs on reconnect, so no replay
 * protocol is needed.
 */
@Path("/repositories/{repoId}/events")
public class RepositoryEventsController {

  @Inject WorkspaceEventBroadcaster broadcaster;

  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Operation(hidden = true)
  public Multi<String> events(@PathParam("repoId") String repoId) {
    return broadcaster.withHeartbeat(broadcaster.subscribe(repoId, null));
  }
}
