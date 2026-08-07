package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.BackupPushService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * What the git host tells this service after it has accepted a push.
 *
 * <p>One route, and it exists so a repository's forge twin does not depend on anybody remembering
 * to push it: qits-artifacts fans its {@code post-receive} out to here, and this schedules the
 * backup. The wire shape is deliberately the one qits-ci's intake already takes, because it is the
 * same hook sending to two receivers and a second spelling of one event would be a second thing to
 * keep in step.
 *
 * <p><b>Fire and forget, all the way down.</b> The hook is in the critical path of somebody's {@code
 * git push}: a slow answer here is a slow push, and an error here would be an error on a push that
 * in fact succeeded. So it answers 204 the moment it has read the body, does the work off-thread,
 * and treats an unknown repository as nothing to do rather than as a 404 — this service is not the
 * authority on which repositories the host serves, and saying so to a hook that cannot act on the
 * answer would only turn an unimportant fact into a failed request.
 */
@Path("/events")
@Consumes(MediaType.APPLICATION_JSON)
public class GitHostEventController {

  @Inject BackupPushService backupPushService;

  /**
   * @param repoId the repository the git host serves — this service's own {@code Repository.id}
   * @param branch the short branch name the push moved, e.g. {@code main}
   * @param oldSha what the ref pointed at before, or the all-zero sha for a create
   * @param newSha what it points at now, or the all-zero sha for a delete
   */
  public static record PostReceiveEventRequest(
      String repoId, String branch, String oldSha, String newSha) {}

  /**
   * Deliberately <b>not</b> {@code @Operation(hidden = true)}: qits-artifacts is generated against
   * this document, so the one route it calls belongs in it.
   */
  @POST
  @Path("/post-receive")
  @Operation(
      summary = "Tell this service the git host accepted a push",
      description =
          "Schedules a backup of the repository onto its forge twin, debounced per repository so a"
              + " push of several branches produces one backup run. Always 204, including for a"
              + " repository this service does not know: the sender is a git hook in the critical"
              + " path of somebody's push and has nothing to do with an error.")
  @APIResponse(responseCode = "204", description = "Accepted; the backup runs off-thread")
  public void postReceive(PostReceiveEventRequest request) {
    if (request != null) {
      backupPushService.onPush(request.repoId());
    }
  }
}
