package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ActiveBuilds;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link ActiveBuilds} port over qits-ci's active-runs listing — a hand-rolled {@code
 * java.net.http} client like every outbound client in this module, because the seam is one GET.
 *
 * <p>{@code qits.projects.release-requests.ci-url} is <b>unset shipped</b>: a deployment names its
 * tier's qits-ci ({@code http://dev-qits-ci:8080}), and unset answers {@code Optional.empty()} —
 * "could not ask" — which the gate reads as "stay pending, the settle window is the floor". Every
 * failure answers the same: an unreachable service, a non-200, an unreadable body. <b>An empty
 * answer is never derived from a failure</b>, the same rule the candidate listing and the backup
 * reconcile state — reading "we could not ask" as "no runs" would wave a release past builds that
 * are still running, which is the exact defect this whole feature exists to close.
 *
 * <p>The identity is the forwarded pair, qits-net's standing posture; it moves to a machine bearer
 * when the auth gate lands, like every other intra-net read.
 */
@ApplicationScoped
@DefaultBean
public class HttpActiveBuilds implements ActiveBuilds {

  private static final Logger LOG = Logger.getLogger(HttpActiveBuilds.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @Override
  public Optional<Integer> activeFor(String repoId, String commitSha) {
    if (ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(ciUrl.get() + "/ci/api/runs/active"))
              .timeout(Duration.ofSeconds(3))
              .header("X-Qits-User", "qits-projects")
              .header("X-Qits-Roles", "qits:system")
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.debugf("qits-ci answered %d for the active listing", response.statusCode());
        return Optional.empty();
      }
      JsonNode runs = MAPPER.readTree(response.body()).path("runs");
      if (!runs.isArray()) {
        return Optional.empty();
      }
      int active = 0;
      for (JsonNode run : runs) {
        if (repoId.equals(run.path("repoId").asText(null))
            && commitSha.equals(run.path("commitSha").asText(null))) {
          active++;
        }
      }
      return Optional.of(active);
    } catch (Exception e) {
      LOG.debugf("Could not read qits-ci's active listing: %s", e.toString());
      return Optional.empty();
    }
  }
}
