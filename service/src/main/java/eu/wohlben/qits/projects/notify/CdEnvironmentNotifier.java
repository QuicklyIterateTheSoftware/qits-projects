package eu.wohlben.qits.projects.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ProjectEnvironmentNotifier;
import eu.wohlben.qits.projects.control.ProjectReconciliation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
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
 * <p><b>And the same request, sent synchronously</b>, for the manual reconcile ({@link
 * #ensureEnvironment}, main-environment-plan.md §5). One {@linkplain #environmentRequest request
 * builder} serves both paths — same url, same payload, same timeouts — because the whole value of
 * the remedy is that it asserts what the creation path asserts. What differs is only the
 * interpretation: the reconcile turns cd's status code into an outcome a person reads, where the
 * creation path turns it into a log line nobody does.
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
   * How long a connect may take, on either path. Short: the receiver is a sibling service on the
   * same network, and the reconcile is waited on by a person.
   */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** How long the whole exchange may take, on either path. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * An <b>instance</b> field, not a static one — the native-image constraint qits-artifacts' {@code
   * CiPostReceiveNotifier} documents at length: a static {@code HttpClient} is created at image
   * build time and native-image refuses the heap it lands in. {@code @ApplicationScoped} keeps it
   * one client per process.
   */
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

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

  /**
   * The reconcile's synchronous half: the same POST, waited on, with cd's answer read as an
   * outcome.
   *
   * <p>The whole 2xx class is {@code CREATED} rather than {@code 201} alone. cd answers 201 today
   * and the wire test pins that, but a successful re-assertion reported as {@code FAILED} because
   * the receiver started answering 200 would send an operator hunting a fault that does not exist —
   * the expensive direction of a wrong guess. {@code 409} is the environment already being there,
   * which is the steady state a reconcile is expected to find and the reason re-asserting is safe
   * at all.
   */
  @Override
  public ProjectReconciliation.EnvironmentAssertion ensureEnvironment(
      String projectId, String name, String slug) {
    HttpRequest request;
    try {
      request = environmentRequest(slug);
    } catch (JsonProcessingException e) {
      return ProjectReconciliation.EnvironmentAssertion.failed(
          "Could not build the environment request: " + e);
    }
    HttpResponse<Void> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this runs on a request thread the container may be shutting
      // down.
      Thread.currentThread().interrupt();
      return ProjectReconciliation.EnvironmentAssertion.failed(
          "Interrupted while asking qits-cd for environment '" + slug + "'.");
    } catch (IOException e) {
      return ProjectReconciliation.EnvironmentAssertion.failed(
          "qits-cd at " + cdUrl + ENVIRONMENTS_PATH + " is unreachable: " + e);
    }
    if (response.statusCode() == 409) {
      return ProjectReconciliation.EnvironmentAssertion.alreadyExists();
    }
    if (response.statusCode() / 100 == 2) {
      return ProjectReconciliation.EnvironmentAssertion.created();
    }
    return ProjectReconciliation.EnvironmentAssertion.failed(
        "qits-cd answered " + response.statusCode() + " creating environment '" + slug + "'.");
  }

  private void post(String projectId, String slug) throws Exception {
    client
        .sendAsync(environmentRequest(slug), HttpResponse.BodyHandlers.discarding())
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

  /**
   * The one request both paths send: {@code POST /cd/api/environments} naming the slug, {@code
   * main} and no applications.
   *
   * <p>Shared on purpose. A reconcile that built its own url or its own payload could report {@code
   * CREATED} for something the creation path never asks for, which is the one way this remedy could
   * be worse than no remedy.
   */
  private HttpRequest environmentRequest(String slug) throws JsonProcessingException {
    String body =
        objectMapper.writeValueAsString(
            Map.of("name", slug, "branch", MAIN_BRANCH, "applications", List.of()));
    return HttpRequest.newBuilder(URI.create(cdUrl + ENVIRONMENTS_PATH))
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }
}
