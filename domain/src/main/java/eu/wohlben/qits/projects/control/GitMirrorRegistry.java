package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.gitmirror.GitCli;
import eu.wohlben.qits.projects.gitmirror.GitMirrors;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place the framework meets {@code qits-projects-gitmirror} (projects-volume-decoupling-plan.md
 * §3.2).
 *
 * <p>That module is a plain library with no CDI, no MicroProfile config and no Quarkus — which is
 * what lets its own suite run offline against throwaway bares, and what would let it move into a
 * daemon of its own without a rewrite. So exactly one bean builds it from config and hands it out,
 * copied from qits-workspaces' bean of the same name.
 */
@ApplicationScoped
public class GitMirrorRegistry {

  /**
   * This service's <b>own</b> data tree — the only one it has. The mirrors are a private cache of
   * repositories this service does not own, so they live where its database and its skeleton
   * scratch already do, and nothing outside this service reads them. The shared volume the bare
   * origins used to sit on is gone from this context entirely
   * (projects-volume-decoupling-plan.md).
   */
  @ConfigProperty(name = "qits.projects.data-dir", defaultValue = "data/projects")
  String dataDir;

  /**
   * The bound on every git call that talks to the git host — clone, fetch, {@code ls-remote} and
   * push alike. One key rather than one per verb: they are the same failure (a wedged host pinning a
   * request thread).
   *
   * <p>Local git in the mirror keeps its unbounded wait, where a bound would only turn slow into
   * broken.
   */
  @ConfigProperty(name = "qits.projects.git.network-timeout-ms", defaultValue = "120000")
  long networkTimeoutMs;

  /**
   * How long a fetched mirror is trusted before a read refreshes it again — bounds UI reads only.
   * Nothing that <i>decides</i> anything reads through it: branch existence is an {@code ls-remote}
   * against the git host, and every flow that is about to write calls a forced refresh first.
   */
  @ConfigProperty(name = "qits.projects.git.mirror-freshness-ms", defaultValue = "5000")
  long freshnessMs;

  @Inject GitHostAddress gitHost;

  private GitMirrors mirrors;

  @PostConstruct
  void build() {
    mirrors =
        new GitMirrors(
            new GitCli(),
            gitHost,
            Path.of(dataDir).toAbsolutePath(),
            Duration.ofMillis(networkTimeoutMs),
            Duration.ofMillis(freshnessMs));
  }

  /** The mirror for a repository. Cheap and lazy — nothing is cloned until objects are needed. */
  public RepoMirror of(String repoId) {
    return mirrors.of(repoId);
  }

  /** Where the mirrors live, for the one test that asserts they are not on the shared volume. */
  public Path root() {
    return mirrors.root();
  }
}
