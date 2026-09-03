package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.QaRunCancellations;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link QaRunCancellations} port over qits-ci's run-cancellation API — a hand-rolled {@code
 * java.net.http} client like every outbound client in this module, because the seam is one POST.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {qits.projects.release-requests.ci-url}/ci/api/runs/cancellations
 *   Authorization: Bearer &lt;machine token, audience qits-ci&gt;
 *   Content-Type: application/json
 *
 *   {"repoId": "&lt;uuid&gt;", "releaseRequestId": "&lt;id&gt;"}
 *
 *   -&gt; 202 (or 200) — the runs of that request are cancelled, or there were none
 * </pre>
 *
 * <p><b>{@code releaseRequestId} is the whole of the scoping and it is never optional.</b> qits-ci
 * knows which runs belong to a release request because {@code ReleaseRequestChanged} told it: the
 * event carries {@code releaseRequestId} beside the {@code mergedSha} it asks for a build of. A
 * cancellation keyed on the repository alone would take a sibling request's green build away
 * seconds before it settled that request, so this body has no repository-wide spelling and must not
 * grow one. {@code repoId} rides along because a run id space is per repository at the far side.
 *
 * <p><b>Every failure is the same answer: log it and carry on.</b> The address being unset, no
 * bearer, a timeout, an unreachable qits-ci, a 404 from a version that has not shipped the route
 * yet, any refusal — the release request's gate is correlated by merged sha and is already correct
 * without this call, so nothing here may be allowed to fail a fold. What the call buys is a build
 * agent, not a decision. See {@link QaRunCancellations} for that argument in full.
 *
 * <p>The credential is the {@code ci} named OIDC client's bearer ({@link IdpCiBearer}), assumed to
 * need {@code qits:system} at the far side. While the client is off — the shipped default, and any
 * no-idp topology — the hop falls back to the forwarded {@code X-Qits-*} pair, the posture {@link
 * HttpActiveBuilds} takes on the same address for the same reason.
 */
@ApplicationScoped
@DefaultBean
public class HttpQaRunCancellations implements QaRunCancellations {

  private static final Logger LOG = Logger.getLogger(HttpQaRunCancellations.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @Inject IdpCiBearer bearer;

  @Override
  public void cancelRunsOf(String repoId, String releaseRequestId) {
    if (ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      return;
    }
    if (releaseRequestId == null || releaseRequestId.isBlank()) {
      // A cancellation with no request on it would be a repository-wide one. Refuse to send it.
      LOG.warn("Refusing to cancel QA runs with no release request named");
      return;
    }
    try {
      Map<String, String> body = new LinkedHashMap<>();
      body.put("repoId", repoId);
      body.put("releaseRequestId", releaseRequestId);
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(ciUrl.get() + "/ci/api/runs/cancellations"))
              .timeout(Duration.ofSeconds(3))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      Optional<String> authorization = bearer.authorization();
      if (authorization.isPresent()) {
        builder.header("Authorization", authorization.get());
      } else {
        builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 202 && response.statusCode() != 200) {
        LOG.debugf(
            "qits-ci answered %d cancelling the runs of release request %s",
            response.statusCode(), releaseRequestId);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOG.debugf(
          "Could not ask qits-ci to cancel the runs of release request %s: %s",
          releaseRequestId, e.toString());
    }
  }
}
