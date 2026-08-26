package eu.wohlben.qits.projects.wiring;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitHostBearer;
import eu.wohlben.qits.projects.control.GitHostException;
import eu.wohlben.qits.projects.control.GitHostRepositories;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The shipped {@link GitHostRepositories}: {@code PUT}/{@code GET}/{@code DELETE} against
 * qits-githost's {@code /git/<repoId>} (projects-volume-decoupling-plan.md §2.3, §3.2).
 *
 * <p>The url is {@link GitHostAddress#fetchUrl}: the same {@code
 * <qits.githost.url>/git/<repoId>} the mirror's clone, fetch and {@code ls-remote} use,
 * which is exactly the lifecycle route's own address (§2.3 names three verbs on that one path). One
 * port supplies it so the two cannot drift apart in a deployment.
 *
 * <p><b>{@code Map}, never a DTO</b>, for the request body and the parsed response — the same
 * discipline qits-githost's {@code GitHostRoutes} keeps on the wire (§2.3: "Responses are {@code
 * JsonObject}, never a DTO"). A record reached only through a bare {@code ObjectMapper} needs
 * {@code @RegisterForReflection} to survive a native image; a {@code Map} needs nothing, so this
 * class adds zero native-image registrations.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static — the native-image rule
 * qits-artifacts' {@code CiPostReceiveNotifier} carries: a static client is built at image-build
 * time and native-image refuses the {@code
 * HttpClientFacade} that lands in the heap. {@code @ApplicationScoped} keeps it one client per
 * process.
 *
 * <p><b>{@code @DefaultBean}</b>, the same posture {@link GitHostAddress}'s shipped implementation
 * takes: {@code GitHostRepositories} being mandatory (a plain {@code @Inject}, not an
 * {@code Instance<T>} — see the port's own javadoc) is a fact about the injection point, not about
 * this class, and it does not conflict with yielding to a test double. Without the annotation a test
 * double and this class are an ambiguous dependency and the build fails at
 * {@code ArcProcessor#validate}, for every test at once — reached here because {@code service}'s
 * test classpath carries both this class and {@code domain}'s test-jar, which is where such a double
 * would live.
 */
@ApplicationScoped
@DefaultBean
public class HttpGitHostRepositories implements GitHostRepositories {

  /** How long a connect may take — the receiver is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject GitHostAddress gitHost;
  @Inject GitHostBearer gitHostBearer;
  @Inject ObjectMapper objectMapper;

  /** The bound on the whole exchange — every wire call this service makes carries it (§3.2). */
  @ConfigProperty(name = "qits.projects.git.network-timeout-ms", defaultValue = "120000")
  long networkTimeoutMs;

  @Override
  public boolean ensure(String repoId, String defaultBranch) {
    String url = gitHost.fetchUrl(repoId);
    String body;
    try {
      body = objectMapper.writeValueAsString(Map.of("defaultBranch", defaultBranch));
    } catch (IOException e) {
      throw new GitHostException("Could not build the create request for " + repoId, e);
    }
    HttpResponse<String> response =
        send(
            request(url)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            "creating");
    if (response.statusCode() == 201) {
      return true;
    }
    if (response.statusCode() == 200) {
      return false;
    }
    throw new GitHostException(
        "qits-githost answered "
            + response.statusCode()
            + " creating "
            + repoId
            + " at "
            + url
            + ": "
            + response.body());
  }

  @Override
  public Optional<HostRepository> find(String repoId) {
    String url = gitHost.fetchUrl(repoId);
    HttpResponse<String> response = send(request(url).GET().build(), "reading");
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw new GitHostException(
          "qits-githost answered "
              + response.statusCode()
              + " reading "
              + repoId
              + " at "
              + url);
    }
    try {
      Map<?, ?> parsed = objectMapper.readValue(response.body(), Map.class);
      Object defaultBranch = parsed.get("defaultBranch");
      return Optional.of(
          new HostRepository(repoId, defaultBranch == null ? null : defaultBranch.toString()));
    } catch (IOException e) {
      throw new GitHostException(
          "Could not read qits-githost's answer for " + repoId + " at " + url, e);
    }
  }

  @Override
  public boolean delete(String repoId) {
    String url = gitHost.fetchUrl(repoId);
    HttpResponse<String> response = send(request(url).DELETE().build(), "deleting");
    if (response.statusCode() == 204) {
      return true;
    }
    if (response.statusCode() == 404) {
      return false;
    }
    throw new GitHostException(
        "qits-githost answered "
            + response.statusCode()
            + " deleting "
            + repoId
            + " at "
            + url
            + ": "
            + response.body());
  }

  private HttpResponse<String> send(HttpRequest request, String verbing) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new GitHostException(
          "qits-githost unreachable " + verbing + " " + request.uri(), e);
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this runs on a request thread the container may be shutting
      // down.
      Thread.currentThread().interrupt();
      throw new GitHostException("Interrupted " + verbing + " " + request.uri(), e);
    }
  }

  private Duration requestTimeout() {
    return Duration.ofMillis(networkTimeoutMs);
  }

  /** Lifecycle calls are machine-only too; never retry them anonymously. */
  private HttpRequest.Builder request(String url) {
    String token =
        gitHostBearer
            .token()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () -> new GitHostException("No machine bearer is available for qits-githost"));
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout())
        .header("Authorization", "Bearer " + token);
  }
}
