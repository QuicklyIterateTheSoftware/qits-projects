package eu.wohlben.qits.projects.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ProjectEnvironmentNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Gives a freshly created project its standing deployment target: one {@code POST
 * /cd/api/environments} naming the project's slug, the {@code main} branch and no applications.
 *
 * <p>The three fields are each a decision (main-environment-plan.md §1):
 *
 * <ul>
 *   <li>{@code name} is the project's <b>slug</b>, not its display name — cd validates an
 *       environment name as a dns label, which is exactly what a slug already is, and a slug cannot
 *       be renamed out from under the environment it named.
 *   <li>{@code branch} is {@code main}, because that is the convention this environment exists for.
 *       Branch-per-epic environments are a later leg and are not created here.
 *   <li>{@code applications} starts <b>empty</b>, and cd accepts that today. Which of a project's
 *       repositories become deployable applications is a later decision, and cd has no
 *       add-application endpoint yet — so an empty list is the honest shape rather than a
 *       placeholder one.
 * </ul>
 *
 * <p><b>Fire-and-forget</b>, the {@code CdBuildNotifier} idiom: this runs on the thread that just
 * created a project, so it must neither block nor throw. A {@code 409} is cd telling us the
 * environment already exists, which is the idempotent no-op a reconciling seed produces on a later
 * boot — debug, not a warning. Everything else is a warning: unlike a missed build announcement,
 * which the next green build carries forward, a project is created exactly once and there is no
 * second attempt coming.
 *
 * <p>The hazard the idiom buys is worth naming: a path that does not match cd's {@code
 * /cd/api/environments} raises no error anywhere and environments simply stop appearing. That is
 * why the literal is pinned here and why {@code CdEnvironmentNotifierTest} asserts the absolute
 * address a request arrives at rather than trusting the constant.
 *
 * <p>It lives in {@code service/} because {@code domain} is web-free; the seam it implements is
 * {@link ProjectEnvironmentNotifier} in {@code projects/control}, and zero implementations is a
 * supported configuration.
 */
@ApplicationScoped
public class CdEnvironmentNotifier implements ProjectEnvironmentNotifier {

  private static final Logger LOG = Logger.getLogger(CdEnvironmentNotifier.class);

  /** cd's own path, appended to the configured base — see {@link #cdUrl}. */
  static final String ENVIRONMENTS_PATH = "/cd/api/environments";

  /** The branch a project's standing environment listens to. */
  static final String MAIN_BRANCH = "main";

  /**
   * An <b>instance</b> field, not a static one — the native-image constraint qits-artifacts' {@code
   * CiPostReceiveNotifier} documents at length: a static {@code HttpClient} is created at image
   * build time and native-image refuses the heap it lands in. {@code @ApplicationScoped} keeps it
   * one client per process.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /**
   * Scheme, host and port — <b>no path</b>. The path is cd's own and belongs to this code rather
   * than to a deployment, the same split {@code qits.observability.url} carries.
   */
  @ConfigProperty(name = "qits.cd.url")
  String cdUrl;

  @Inject ObjectMapper objectMapper;

  @Override
  public void onProjectCreated(String projectId, String name, String slug) {
    try {
      post(projectId, slug);
    } catch (Exception e) {
      LOG.debugf("Environment creation for project %s skipped: %s", projectId, e.toString());
    }
  }

  private void post(String projectId, String slug) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of("name", slug, "branch", MAIN_BRANCH, "applications", List.of()));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(cdUrl + ENVIRONMENTS_PATH))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    client
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, failure) -> {
              if (failure != null) {
                LOG.warnf(
                    "Environment creation for project %s failed: %s",
                    projectId, failure.toString());
              } else if (response.statusCode() == 409) {
                LOG.debugf(
                    "Environment '%s' already exists in qits-cd — nothing to create for project"
                        + " %s.",
                    slug, projectId);
              } else if (response.statusCode() >= 400) {
                LOG.warnf(
                    "Environment creation for project %s rejected: %d",
                    projectId, response.statusCode());
              }
            });
  }
}
