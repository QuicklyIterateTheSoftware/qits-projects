package eu.wohlben.qits.projects.releasehost;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The separately audience-bound bearer for the release executor's calls to qits-workspaces — the
 * {@code wiring/IdpGitHostBearer} shape on the {@code workspaces} named client.
 *
 * <p>Empty when the client is disabled (the shipped default) or the mint fails; the caller then
 * falls back to the forwarded {@code X-Qits-*} pair rather than failing the release — this hop
 * predates its credential and a no-idp topology still releases.
 */
@ApplicationScoped
public class IdpWorkspacesBearer {

  private static final Logger LOG = Logger.getLogger(IdpWorkspacesBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.workspaces.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("workspaces") OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
  public Optional<String> authorization() {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank())
          .map(value -> "Bearer " + value);
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-workspaces: %s", e.toString());
      return Optional.empty();
    }
  }
}
