package eu.wohlben.qits.projects.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Guards the pre-expiry refresh window on both machine-client audiences.
 *
 * <p>qits-idp grants service tokens for an hour. Without this skew, {@code TokensHelper} can reuse
 * a bearer in its JWT {@code exp} second: the resource server has already rejected it, while the
 * helper refreshes only on the following whole second. The result is a recurring hourly 401 from
 * the first call to qits-containers or qits-githost in that gap.
 *
 * <p>This is a deployed-config assertion, so it is a {@link QuarkusTest}; a plain config lookup
 * would not read this module's {@code application.properties}.
 */
@QuarkusTest
class OidcClientRefreshConfigTest {

  private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

  @Inject Config config;

  @Test
  void bothMachineAudiencesRefreshBeforeTheirBearerExpires() {
    assertEquals(REFRESH_SKEW, duration("quarkus.oidc-client.refresh-token-time-skew"));
    assertEquals(
        REFRESH_SKEW, duration("quarkus.oidc-client.githost.refresh-token-time-skew"));
  }

  private Duration duration(String key) {
    return config
        .getOptionalValue(key, Duration.class)
        .orElseThrow(() -> new AssertionError(key + " is not set — hourly machine 401s can return"));
  }
}
