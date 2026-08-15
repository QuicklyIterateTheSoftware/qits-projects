package eu.wohlben.qits.projects.gitmirror;

import java.util.Optional;

/**
 * Where a repository answers as a git remote — the platform's own git host, and the only remote
 * this interface ever names. An external backup remote (a repository's own {@code url}) is never
 * asked here: it is deployment-external state, passed explicitly to the methods that touch it
 * ({@link RepoMirror#cloneFrom} and {@link RepoMirror#fetchIntoFetchHead}), so this module stays
 * ignorant of rows.
 *
 * <p><b>Two methods for one string, and the split is load-bearing.</b> A deployment returns the same
 * url from both. A test double returns the same url from both too — but {@link #pushUrl} is asked
 * <em>once, immediately before a push</em>, which is the only instant a lost race is about, so a
 * double can stage a second writer there. Reads and fetches must not consume that hook, which is why
 * they ask {@link #fetchUrl} instead.
 */
public interface GitRemotes {

  /** The remote to read from — {@code ls-remote} and the mirror's fetch. */
  String fetchUrl(String repoId);

  /** The remote to push to, asked once per push. */
  String pushUrl(String repoId);

  /**
   * The verified machine bearer to send only to this platform remote. Empty preserves the offline
   * library/test shape; production wiring supplies it and fails before network I/O when it cannot.
   */
  default Optional<String> httpExtraHeader() {
    return Optional.empty();
  }
}
