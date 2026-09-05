package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ReleasedBranchWorkspaces;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link ReleasedBranchWorkspaces}: one POST to qits-workspaces' branch-resolution door.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {qits.projects.release-requests.workspaces-url}/workspaces/api/branches/resolution?repositoryId=&lt;repoId&gt;
 *   Authorization: Bearer &lt;machine token, audience qits-workspaces&gt;
 *   Content-Type: application/json
 *
 *   {"branch": "…", "target": "&lt;version&gt;", "commit": "&lt;released sha&gt;",
 *    "result": "released as &lt;version&gt;"}
 *
 *   -&gt; 200 {"resolved": true, "workspaceId": 41}   the workspace on that branch was torn down
 *   -&gt; 200 {"resolved": false}                     nothing stood on it — the ordinary answer
 * </pre>
 *
 * <p><b>It is a workspace-lifecycle call and never a release verb.</b> qits-workspaces' release door
 * left on 2026-09-03 and stays gone; what travels here is a fact about a branch — it was released and
 * is deleted — and {@code target}/{@code commit} ride along only so the far side's history entry can
 * be followed back to the release. Nothing here merges, tags, pushes or promotes, and this adapter
 * must not grow a second verb on the grounds that the address is configured again.
 *
 * <h2>The key that came back</h2>
 *
 * <p>{@code qits.projects.release-requests.workspaces-url} <b>retired with that door</b> and returns
 * with a strictly narrower job: one lifecycle call on one path, made after a release rather than to
 * perform one. Unlike the other two addresses of this flow it ships <b>set</b>, to the in-network
 * default, because it is not a switch for the flow — no release depends on the answer — and an unset
 * one would only lose reaps. Blank switches it off explicitly, with a line in the log.
 *
 * <h2>Every failure is one behaviour</h2>
 *
 * <p>No address, no bearer, a timeout, an unreachable or refusing qits-workspaces, an answer that
 * will not parse: one WARN naming the repository and the branch and the reason, and a normal return.
 * The port's contract is that it never throws — the release has already happened and a workspace that
 * was not reaped is a reap to be made by hand, not a release to be undone. The WARN is at {@code
 * warn} rather than {@code debug} deliberately: unlike the QA-run cancellation beside it, a call lost
 * here leaves a container, a volume and a commissioned credential standing until somebody acts.
 *
 * <p><b>There is no forwarded-header fallback.</b> The two hops on qits-ci ({@code HttpActiveBuilds},
 * {@code HttpQaRunCancellations}) fall back to the {@code X-Qits-*} pair because a missed
 * cancellation costs a build agent; this one asks another context to destroy a container, and a call
 * this service cannot authenticate is one it does not make.
 *
 * <p><b>{@code Map}, never a DTO</b>, in both directions — {@code wiring/HttpGitHostRepositories}'
 * discipline and for its reason: a record reached through a bare {@link ObjectMapper} needs
 * {@code @RegisterForReflection} to survive a native image and a {@code Map} needs nothing, so this
 * class adds zero native-image registrations and there is no {@code WorkspacesWireReflection} beside
 * {@code containershost/ContainersWireReflection}.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static: a static one is built at
 * image-build time and native-image refuses the {@code HttpClientFacade} that lands in the heap.
 * {@code NativeImageContractTest} pins that.
 */
@ApplicationScoped
@DefaultBean
public class HttpReleasedBranchWorkspaces implements ReleasedBranchWorkspaces {

  private static final Logger LOG = Logger.getLogger(HttpReleasedBranchWorkspaces.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a connect may take — qits-workspaces is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on the whole exchange. The far side stops a container inside it. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> workspacesUrl;

  @Inject WorkspacesBearer bearer;

  @Override
  public void branchReleased(String repoId, String branch, String version, String releasedSha) {
    if (workspacesUrl.isEmpty() || workspacesUrl.get().isBlank()) {
      warn(repoId, branch, "qits.projects.release-requests.workspaces-url is unset");
      return;
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      warn(repoId, branch, "no machine bearer for qits-workspaces is available");
      return;
    }
    String url =
        workspacesUrl.get()
            + "/workspaces/api/branches/resolution?repositoryId="
            + URLEncoder.encode(repoId, StandardCharsets.UTF_8);
    try {
      Map<String, String> body = new LinkedHashMap<>();
      body.put("branch", branch);
      body.put("target", version);
      body.put("commit", releasedSha);
      body.put("result", "released as " + version);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", authorization.get())
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        warn(repoId, branch, "qits-workspaces answered " + response.statusCode() + ": "
            + response.body());
        return;
      }
      report(repoId, branch, response.body());
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this runs on the release worker, which shutdown interrupts.
      Thread.currentThread().interrupt();
      warn(repoId, branch, "interrupted");
    } catch (Exception e) {
      warn(repoId, branch, e.toString());
    }
  }

  /**
   * The answer, read for the log and for nothing else. A resolution is a container, a volume and a
   * credential gone — one INFO line is what makes that traceable from this side. {@code
   * resolved:false} is the ordinary case, so it is a debug line at most.
   */
  private void report(String repoId, String branch, String body) {
    try {
      Map<?, ?> answer = MAPPER.readValue(body, Map.class);
      if (Boolean.TRUE.equals(answer.get("resolved"))) {
        LOG.infof(
            "qits-workspaces resolved workspace %s on the released branch %s of %s",
            answer.get("workspaceId"), branch, repoId);
      } else {
        LOG.debugf("No workspace stood on the released branch %s of %s", branch, repoId);
      }
    } catch (Exception e) {
      warn(repoId, branch, "could not read the answer: " + e);
    }
  }

  private static void warn(String repoId, String branch, String reason) {
    LOG.warnf(
        "Could not ask qits-workspaces to resolve the workspace on the released branch %s of %s:"
            + " %s. The workspace, if there is one, stays ACTIVE until it is abandoned by hand.",
        branch, repoId, reason);
  }
}
