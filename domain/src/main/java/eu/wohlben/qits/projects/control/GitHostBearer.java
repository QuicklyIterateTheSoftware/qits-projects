package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * The short-lived bearer a projects process presents exclusively to qits-githost.
 *
 * <p>The port lives beside {@link GitHostAddress} so the mirror library remains a plain git
 * library: it receives the credential through its remote, while the service deployment decides how
 * to mint it. An empty result is deliberately not an anonymous fallback. The caller rejects the
 * qits-githost operation before opening a socket.
 */
public interface GitHostBearer {

  /** A current access token for qits-githost, or empty when it could not be obtained. */
  Optional<String> token();
}
