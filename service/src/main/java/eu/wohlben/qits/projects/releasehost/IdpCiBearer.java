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
 * The audience-bound machine bearer this service presents to qits-ci — {@code wiring/IdpGitHostBearer}'s
 * sibling on the {@code ci} named client (audience {@code qits-ci}), empty on the same terms.
 *
 * <p>Two hops hold it and both are about a release request's gate: {@link HttpActiveBuilds} reads
 * the active-runs listing, and {@link HttpQaRunCancellations} asks for a superseded request's runs
 * to be stopped. Empty is a supported answer for both — with the named client disabled (the shipped
 * default, and any no-idp topology) each falls back to the forwarded {@code X-Qits-*} pair rather
 * than refusing, because unlike the git host's release primitives neither of these is load-bearing:
 * an unanswerable listing keeps a request pending and an unmade cancellation costs a build agent.
 */
@ApplicationScoped
public class IdpCiBearer {

  private static final Logger LOG = Logger.getLogger(IdpCiBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.ci.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("ci") OidcClient oidcClient;

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
      LOG.warnf("Could not get a machine token for qits-ci: %s", e.toString());
      return Optional.empty();
    }
  }
}
