package eu.wohlben.qits.projects.idphost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentCredentialException;
import eu.wohlben.qits.projects.refinementhost.RefinementCredentials;
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
 * {@link RefinementCredentials} over qits-idp's commission API — the refinement sibling of
 * {@link IdpAgentCredentials}, one directory over, same shape on purpose: HTTP Basic with this
 * service's own oidc pair, {@code Map}s and never DTOs (no native-image registration to owe), an
 * instance {@link HttpClient}, and absent-as-shipped when {@code
 * quarkus.oidc-client.client-enabled} is off. The only differences are the context kind and the
 * context id (a refinement row id rather than a project id). That class carries the full argument
 * for every one of these choices.
 */
@ApplicationScoped
@DefaultBean
public class IdpRefinementCredentials implements RefinementCredentials {

  private static final Logger LOG = Logger.getLogger(IdpRefinementCredentials.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject ObjectMapper objectMapper;

  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean tokensEnabled;

  @ConfigProperty(name = "quarkus.oidc-client.auth-server-url")
  String authServerUrl;

  @ConfigProperty(name = "quarkus.oidc-client.client-id")
  String clientId;

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
              + " refinement credential can be commissioned. Set"
              + " QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET.");
      return false;
    }
    return true;
  }

  @Override
  public Commissioned commission(long refinementId) {
    String body;
    try {
      body =
          objectMapper.writeValueAsString(
              Map.of("contextKind", CONTEXT_KIND, "contextId", Long.toString(refinementId)));
    } catch (IOException e) {
      throw new AgentCredentialException("Could not build the commission request", false, e);
    }
    HttpResponse<String> response =
        send(
            request(clientsUrl())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            "commissioning a credential for refinement " + refinementId);
    if (response.statusCode() != 201) {
      throw refusal("commission a credential for refinement " + refinementId, response);
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
      LOG.warnf("Could not decommission %s: %s", commissionedClientId, e.getMessage());
    }
  }

  @Override
  public List<Commission> listRefinementCommissions() {
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
      // Somebody else's context kind is somebody else's business, even under this owner — the
      // agent-container commissions in particular.
      if (!CONTEXT_KIND.equals(text(fields.get("contextKind")))) {
        continue;
      }
      String commissionedId = text(fields.get("clientId"));
      String refinementId = text(fields.get("contextId"));
      if (commissionedId != null && refinementId != null) {
        commissions.add(new Commission(commissionedId, refinementId));
      }
    }
    return commissions;
  }

  private String clientsUrl() {
    String base =
        authServerUrl.endsWith("/")
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
      throw new AgentCredentialException("qits-idp unreachable " + doing + ": " + e, true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentCredentialException("Interrupted " + doing, false, e);
    }
  }

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
