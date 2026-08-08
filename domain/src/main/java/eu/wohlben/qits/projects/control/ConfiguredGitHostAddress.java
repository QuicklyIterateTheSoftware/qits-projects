package eu.wohlben.qits.projects.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The shipped {@link GitHostAddress}: {@code qits.artifacts.url} plus the {@code
 * /artifacts/git/<repoId>} route qits-platform-artifacts serves
 * (projects-volume-decoupling-plan.md §2.3).
 *
 * <p><b>{@code @DefaultBean}.</b> It yields to any other bean of the type, which is what lets a test
 * double point every mirror at a local bare with no change on the production side. Keep the
 * annotation: dropping it makes the two an ambiguous dependency and the build fails at {@code
 * ArcProcessor#validate}, for every test at once.
 *
 * <p>The path segment is spelled here and not configured. It is the git host's contract, not a
 * deployment's choice — {@code GitHostRoutes.BASE} in qits-platform-artifacts is the same literal —
 * and a second copy in a properties file would be a second place for it to drift.
 */
@ApplicationScoped
@DefaultBean
public class ConfiguredGitHostAddress implements GitHostAddress {

  /**
   * Scheme, host and port with <b>no path</b> — the shape {@code qits.observability.url} already
   * uses, so one value works whether the call goes direct on qits-net or through the gateway.
   */
  @ConfigProperty(name = "qits.artifacts.url", defaultValue = "http://qits-platform-artifacts:8080")
  String artifactsUrl;

  @Override
  public String fetchUrl(String repoId) {
    String base = artifactsUrl == null ? "" : artifactsUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/artifacts/git/" + repoId;
  }

  /** One address, so reads and writes cannot drift apart in a deployment. */
  @Override
  public String pushUrl(String repoId) {
    return fetchUrl(repoId);
  }
}
