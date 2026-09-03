package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.GitHostBearer;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link BackingBranchMerger} port over qits-githost's octopus-merge primitive — {@code POST
 * /githost/api/repositories/{repoId}/merges}. Hand-rolled {@code java.net.http}, this package's
 * standing shape ({@link HttpReleaseGitHost}, {@link HttpActiveBuilds}).
 *
 * <p>{@code qits.projects.release-requests.githost-url} is <b>unset shipped</b>, the same optional
 * posture {@code …ci-url} beside it takes; a deployment names its tier's git host. Unset, every
 * fold refuses with a detail that says so — a visible stall the sweep keeps re-folding, never a
 * silent one. It is a separate key from {@code qits.githost.url} (the address the mirrors clone
 * from) on purpose while the release flow is being stood up: the flow is opt-in per tier and must be
 * switchable without touching how this service fetches git.
 *
 * <p><b>The credential is the machine bearer this service already holds for qits-githost</b> —
 * {@code wiring/IdpGitHostBearer} over the {@code GitHostBearer} port, the {@code githost} named
 * OIDC client, audience {@code qits-githost}. The far side guards these routes with {@code
 * qits:system}, which a forwarded {@code X-Qits-*} pair cannot carry, so there is deliberately
 * <b>no header fallback</b> here: with the client disabled the fold refuses rather than sending an
 * anonymous request the git host would answer 401 to.
 *
 * <p><b>Never throws, and every answer is classified.</b> The three success words map to the port's
 * three; a {@code 409 merge-conflict} is the conflict with its paths forwarded unchanged; and every
 * other answer — {@code ref-moved}, a 404, a 5xx, a timeout, an unconfigured address — is {@link
 * BackingBranchMerger.Outcome#unreachable}, a fact about the moment that the sweep asks again about.
 * {@code ref-moved} lands there deliberately: it means a concurrent writer moved the backing branch,
 * and the answer to that is to fold again, not to stop.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static — the native-image rule {@code
 * HttpGitHostRepositories} carries.
 */
@ApplicationScoped
@DefaultBean
public class HttpBackingBranchMerger implements BackingBranchMerger {

  private static final Logger LOG = Logger.getLogger(HttpBackingBranchMerger.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A fold is JGit in-core over a bare — bounded, but a large repository is not instant. */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @ConfigProperty(name = "qits.projects.release-requests.githost-url")
  Optional<String> githostUrl;

  @Inject GitHostBearer bearer;

  @Override
  public Outcome merge(String repoId, String target, List<String> sources, String message) {
    if (githostUrl.isEmpty() || githostUrl.get().isBlank()) {
      return Outcome.unreachable(
          "qits.projects.release-requests.githost-url is not configured; nothing can fold this"
              + " request's sources");
    }
    Optional<String> token = bearer.token();
    if (token.isEmpty()) {
      return Outcome.unreachable(
          "No machine bearer is available for qits-githost; the merge door takes qits:system and"
              + " refuses an anonymous caller");
    }
    String address =
        githostUrl.get() + "/githost/api/repositories/" + encode(repoId) + "/merges";
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("target", target);
      body.put("sources", sources);
      body.put("message", message);
      // The author both halves or neither, the far side's rule. This service is the one folding.
      body.put("author", Map.of("name", "qits-projects", "email", "qits-projects@qits.internal"));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(address))
              .timeout(CALL_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token.get())
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return success(response.body());
      }
      if (response.statusCode() == 409) {
        JsonNode answer = MAPPER.readTree(response.body());
        if ("merge-conflict".equals(answer.path("error").asText(""))) {
          return Outcome.conflict(
              answer.path("target").asText(target), conflictsOf(answer.path("conflicts")));
        }
      }
      return Outcome.unreachable(
          "qits-githost answered " + response.statusCode() + ": " + clip(response.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.unreachable("interrupted while folding " + target);
    } catch (Exception e) {
      LOG.warnf("qits-githost could not be reached to fold %s: %s", target, e.toString());
      return Outcome.unreachable("qits-githost could not be reached: " + e);
    }
  }

  /** The 200 body: {@code target}, {@code sha}, {@code outcome}, {@code parents}, {@code skipped}. */
  private static Outcome success(String body) throws Exception {
    JsonNode answer = MAPPER.readTree(body);
    String sha = answer.path("sha").asText(null);
    if (sha == null || sha.isBlank()) {
      return Outcome.unreachable("qits-githost answered 200 with no sha: " + clip(body));
    }
    List<String> parents = new ArrayList<>();
    answer.path("parents").forEach(node -> parents.add(node.asText()));
    return switch (answer.path("outcome").asText("")) {
      case "merged" -> Outcome.merged(sha, parents);
      case "fast-forward" -> Outcome.fastForward(sha, parents);
      case "unchanged" -> Outcome.unchanged(sha);
      default ->
          Outcome.unreachable(
              "qits-githost answered 200 with an outcome nothing here knows: " + clip(body));
    };
  }

  private static List<Conflict> conflictsOf(JsonNode conflicts) {
    List<Conflict> paths = new ArrayList<>();
    conflicts.forEach(
        node ->
            paths.add(
                new Conflict(
                    node.path("path").asText(null),
                    node.path("head").asText(null),
                    node.path("headSha").asText(null),
                    node.path("reason").asText(null))));
    return paths;
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
