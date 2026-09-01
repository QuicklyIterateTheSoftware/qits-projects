package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link ReleaseExecutor} port over qits-workspaces' <b>execution arm</b>
 * ({@code /branches/execute-release}) — the door-split half only gated execution calls, so this
 * address stays correct when the public door becomes request-creating. Hand-rolled {@code
 * java.net.http}, the module's standing shape.
 *
 * <p>{@code qits.projects.release-requests.workspaces-url} is <b>unset shipped</b>; a deployment
 * names its tier's qits-workspaces. Unset, every execution refuses with a detail that says so — a
 * visible stall the sweep keeps retrying, never a silent one.
 *
 * <p>The door is addressed by the public pair when the repository has a name and by the storage id
 * when it does not — the same two arms the door itself keeps. The acting identity is the request's
 * own requester over the forwarded pair, so the door's audit trail names the person who asked
 * rather than this service; the role is the door's required {@code qits:admin}. Both move to a
 * machine bearer with the door split.
 *
 * <p><b>Never throws, and every answer is classified.</b> A timeout, an unreachable door, a 5xx and
 * the door's own retry-me 409s ({@code NOT_FAST_FORWARD}, {@code VERSION_ALREADY_RELEASED}) are
 * {@link Outcome#refusedRetryable} — the moment failed and the sweep asks again. Every other
 * refusal is about the request itself ({@code ALREADY_INTEGRATED}, a branch that is gone, a
 * malformed ask) and answers the same forever, so it is a plain {@link Outcome#refused} the sweep
 * leaves alone until a re-arm changes the ask. The generous timeout is the door's own shape — a
 * release is a merge, a tag and two pushes.
 */
@ApplicationScoped
@DefaultBean
public class HttpReleaseExecutor implements ReleaseExecutor {

  private static final Logger LOG = Logger.getLogger(HttpReleaseExecutor.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> workspacesUrl;

  @Override
  public Outcome release(
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String expectedSha,
      String summary,
      String requester) {
    if (workspacesUrl.isEmpty() || workspacesUrl.get().isBlank()) {
      return Outcome.refusedRetryable(
          "qits.projects.release-requests.workspaces-url is not configured; nothing can execute"
              + " this release");
    }
    String address;
    if (projectId != null && repoName != null) {
      address =
          workspacesUrl.get()
              + "/workspaces/api/branches/execute-release?projectId="
              + encode(projectId)
              + "&repositoryName="
              + encode(repoName);
    } else {
      address =
          workspacesUrl.get() + "/workspaces/api/branches/execute-release?repositoryId=" + encode(repoId);
    }
    try {
      java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
      fields.put("branch", branch);
      fields.put("summary", summary == null ? "" : summary);
      if (expectedSha != null) {
        // The pin: the gates evaluated this commit, and the door refuses (HEAD_MOVED) rather than
        // landing a head that moved past it. Omitted rather than nulled for a door that predates
        // the field.
        fields.put("expectedSha", expectedSha);
      }
      String body = MAPPER.writeValueAsString(fields);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(address))
              .timeout(Duration.ofSeconds(120))
              .header("Content-Type", "application/json")
              .header("X-Qits-User", requester == null || requester.isBlank() ? "qits-projects" : requester)
              .header("X-Qits-Roles", "qits:admin")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        JsonNode answer = MAPPER.readTree(response.body());
        String version = answer.path("version").asText(null);
        if (version == null || version.isBlank()) {
          return Outcome.refused("The door answered 200 with no version: " + clip(response.body()));
        }
        return Outcome.released(version);
      }
      String detail =
          "The door answered " + response.statusCode() + ": " + clip(response.body());
      return retryable(response) ? Outcome.refusedRetryable(detail) : Outcome.refused(detail);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.refusedRetryable("interrupted while asking the door");
    } catch (Exception e) {
      LOG.warnf("The release door could not be reached: %s", e.toString());
      return Outcome.refusedRetryable("The door could not be reached: " + e);
    }
  }

  /**
   * Whether asking again can change the answer. A 5xx is the door's moment, not the request. A 409
   * is the door's conflict vocabulary and splits by its {@code reason}: {@code NOT_FAST_FORWARD}
   * and {@code VERSION_ALREADY_RELEASED} are the two the door itself documents as retry-and-it-
   * works (a racing writer, a same-second version tie); everything else there — {@code
   * ALREADY_INTEGRATED}, {@code PUSH_REJECTED}, the conflict pair, {@code HEAD_MOVED} — states a
   * fact about the ask that only a new push changes. Every other 4xx (a branch that is gone, a
   * malformed request) is likewise about the ask.
   */
  private static boolean retryable(HttpResponse<String> response) {
    if (response.statusCode() >= 500) {
      return true;
    }
    if (response.statusCode() != 409) {
      return false;
    }
    try {
      String reason = MAPPER.readTree(response.body()).path("reason").asText("");
      return "NOT_FAST_FORWARD".equals(reason) || "VERSION_ALREADY_RELEASED".equals(reason);
    } catch (Exception e) {
      return false;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String clip(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 300 ? body : body.substring(0, 300) + "…";
  }
}
