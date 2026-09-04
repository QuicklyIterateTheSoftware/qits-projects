package eu.wohlben.qits.projects.error;

/**
 * A manifest could not be read, parsed or addressed while stamping a release version into it.
 *
 * <p><b>Loud on purpose.</b> A bump that silently skipped a file would ship a release whose poms and
 * package.jsons still carry the previous version, and that is discovered much later — in a registry,
 * by somebody who cannot tell which commit it came from. So every absence the bump engine cares
 * about (a {@code <module>} with no pom, a manifest with no {@code version}, a lockfile whose root
 * package entry has none) is this exception rather than a skip.
 *
 * <p>A plain {@link RuntimeException} and deliberately <b>not</b> a {@link DomainException}: it
 * never reaches a REST caller. Its only caller is the release executor, which classifies it as a
 * refusal about the ask — a malformed manifest answers the same on every retry — and puts the
 * sentence on the request's detail for a person to act on.
 */
public class ManifestBumpException extends RuntimeException {

  public ManifestBumpException(String message) {
    super(message);
  }

  public ManifestBumpException(String message, Throwable cause) {
    super(message, cause);
  }
}
