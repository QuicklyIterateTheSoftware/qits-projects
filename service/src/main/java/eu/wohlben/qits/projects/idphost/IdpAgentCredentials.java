package eu.wohlben.qits.projects.idphost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentCredentialException;
import eu.wohlben.qits.projects.agenthost.AgentCredentials;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link AgentCredentials} over qits-idp's commission API — {@code POST}, {@code DELETE} and
 * {@code GET} on {@code <auth-server-url>/api/clients}.
 *
 * <p><b>HTTP Basic with this service's own client credentials, not a bearer.</b> That is the API's
 * design and it is the mechanism that adds nothing: a caller here already holds an idp client id and
 * secret — it is how it gets tokens at all — so there is no new audience to configure and no
 * bearer-validation stack inside the service that issues the bearers. The pair comes from
 * {@code quarkus.oidc-client.client-id} and {@code quarkus.oidc-client.credentials.secret}, and the
 * base url from {@code quarkus.oidc-client.auth-server-url}: one set of keys, so a deployment that
 * turned machine auth on has already configured this.
 *
 * <p><b>Absent is the shipped configuration.</b> {@code quarkus.oidc-client.client-enabled=false}
 * means this process holds no secret, so it can authenticate to nothing and
 * {@link #enabled()} answers false before any url is built. A blank secret with the switch on is the
 * same answer with a warning: a deployment half-way through turning idp on must not fail every
 * ensure, it must behave as it did yesterday and say so once.
 *
 * <p><b>{@code Map}, never a DTO</b>, for both directions — the discipline {@code
 * wiring/HttpGitHostRepositories} keeps and for the same reason: a record reached through a bare
 * {@code ObjectMapper} needs {@code @RegisterForReflection} to survive a native image, and a
 * {@code Map} needs nothing. This class adds zero native-image registrations, which is why there is
 * no {@code IdpWireReflection} beside {@code ContainersWireReflection}.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static: a static one is built at
 * image-build time and native-image refuses the facade that lands in the heap.
 */
@ApplicationScoped
@DefaultBean
public class IdpAgentCredentials implements AgentCredentials {

  private static final Logger LOG = Logger.getLogger(IdpAgentCredentials.class);

  /** How long a connect may take — qits-idp is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject ObjectMapper objectMapper;

  /**
   * The same switch {@code ContainersClientProducer} reads, and read from the extension's own key
   * for the same reason: one value decides whether this process has a credential at all, and a
   * second key of ours would be a second thing to get wrong.
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean tokensEnabled;

  /** The idp's base — {@code …/idp}, the same value the token endpoint is joined onto. */
  @ConfigProperty(name = "quarkus.oidc-client.auth-server-url")
  String authServerUrl;

  @ConfigProperty(name = "quarkus.oidc-client.client-id")
  String clientId;

  /** Absent whenever the switch is off, and the switch being on with no secret is a warning. */
  @ConfigProperty(name = "quarkus.oidc-client.credentials.secret")
  Optional<String> clientSecret;

  @ConfigProperty(name = "qits.projects.agent-credentials.request-timeout")
  Duration requestTimeout;

  @Override
  public boolean enabled() {
    if (!tokensEnabled) {
      return false;
    }
    if (secret().isEmpty()) {
      LOG.warn(
          "quarkus.oidc-client.client-enabled is on but no client secret is configured, so no"
              + " agent-container credential can be commissioned. Set"
              + " QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET.");
      return false;
    }
    return true;
  }

  @Override
  public Commissioned commission(String projectId) {
    String body;
    try {
      body =
          objectMapper.writeValueAsString(
              Map.of("contextKind", CONTEXT_KIND, "contextId", projectId));
    } catch (IOException e) {
      // A two-entry map of strings. Unreachable, and not retryable if it ever were.
      throw new AgentCredentialException("Could not build the commission request", false, e);
    }
    HttpResponse<String> response =
        send(
            request(clientsUrl())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            "commissioning a credential for project " + projectId);
    if (response.statusCode() != 201) {
      throw refusal("commission a credential for project " + projectId, response);
    }
    Map<?, ?> answer = parseObject(response.body(), "the commission answer");
    String commissionedId = text(answer.get("clientId"));
    String commissionedSecret = text(answer.get("secret"));
    if (commissionedId == null || commissionedSecret == null) {
      throw new AgentCredentialException(
          "qits-idp answered a commission with no clientId or no secret", false);
    }
    return new Commissioned(commissionedId, commissionedSecret);
  }

  @Override
  public void decommission(String commissionedClientId) {
    if (!enabled()) {
      return;
    }
    try {
      HttpResponse<String> response =
          send(
              request(clientsUrl() + "/" + commissionedClientId).DELETE().build(),
              "decommissioning " + commissionedClientId);
      // 404 is the state this asks for: idp no longer holds the client.
      if (response.statusCode() != 204 && response.statusCode() != 404) {
        LOG.warnf(
            "qits-idp answered %d decommissioning %s: %s",
            response.statusCode(), commissionedClientId, response.body());
      }
    } catch (AgentCredentialException e) {
      // Best-effort by contract — the reconcile comes round again.
      LOG.warnf("Could not decommission %s: %s", commissionedClientId, e.getMessage());
    }
  }

  @Override
  public List<Commission> listAgentContainerCommissions() {
    if (!enabled()) {
      return List.of();
    }
    HttpResponse<String> response;
    try {
      response = send(request(clientsUrl()).GET().build(), "listing this service's commissions");
    } catch (AgentCredentialException e) {
      LOG.warnf("Could not list this service's commissions: %s", e.getMessage());
      return List.of();
    }
    if (response.statusCode() != 200) {
      LOG.warnf(
          "qits-idp answered %d listing this service's commissions: %s",
          response.statusCode(), response.body());
      return List.of();
    }
    List<?> rows;
    try {
      rows = objectMapper.readValue(response.body(), List.class);
    } catch (IOException e) {
      LOG.warnf("Could not read qits-idp's listing of this service's commissions: %s", e.toString());
      return List.of();
    }
    List<Commission> commissions = new ArrayList<>();
    for (Object row : rows) {
      if (!(row instanceof Map<?, ?> fields)) {
        continue;
      }
      // Somebody else's context kind is somebody else's business, even under this owner.
      if (!CONTEXT_KIND.equals(text(fields.get("contextKind")))) {
        continue;
      }
      String commissionedId = text(fields.get("clientId"));
      String projectId = text(fields.get("contextId"));
      if (commissionedId != null && projectId != null) {
        commissions.add(new Commission(commissionedId, projectId));
      }
    }
    return commissions;
  }

  /** {@code <auth-server-url>/api/clients}, with no double slash however the base is written. */
  private String clientsUrl() {
    String base = authServerUrl.endsWith("/")
        ? authServerUrl.substring(0, authServerUrl.length() - 1)
        : authServerUrl;
    return base + "/api/clients";
  }

  private HttpRequest.Builder request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout)
        .header("Authorization", basic());
  }

  private String basic() {
    String pair = clientId + ":" + secret().orElse("");
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  private Optional<String> secret() {
    return clientSecret.filter(value -> !value.isBlank());
  }

  private HttpResponse<String> send(HttpRequest request, String doing) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      // Nobody answered, which is a statement about the moment and not about the request.
      throw new AgentCredentialException("qits-idp unreachable " + doing + ": " + e, true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentCredentialException("Interrupted " + doing, false, e);
    }
  }

  /**
   * A refusal, classified.
   *
   * <p>401 and 403 are held through for the reason {@code ContainersAgentRuntime.holdThrough}
   * records: across an idp cutover they are a statement about the moment — this service's own
   * credential belongs to the idp that was just replaced — and the same call succeeds a minute
   * later. A 5xx is the same kind of answer. Everything else is about the request and no window
   * fixes it.
   */
  private static AgentCredentialException refusal(String doing, HttpResponse<String> response) {
    int status = response.statusCode();
    boolean retryable = status == 401 || status == 403 || status >= 500;
    return new AgentCredentialException(
        "qits-idp answered " + status + " asked to " + doing + ": " + response.body(), retryable);
  }

  private Map<?, ?> parseObject(String body, String what) {
    try {
      return objectMapper.readValue(body, Map.class);
    } catch (IOException e) {
      throw new AgentCredentialException("Could not read " + what + " from qits-idp", false, e);
    }
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
