package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;

/**
 * The test-side {@link GitHostAddress}: a local bare repository under {@code
 * target/qits-test-host/<repoId>.git}, standing in for the git host
 * (projects-volume-decoupling-plan.md §5).
 *
 * <p>It wins over the shipped {@link ConfiguredGitHostAddress} with no change on your part, because
 * that one is {@code @DefaultBean} and yields to any other bean of the type.
 *
 * <p><b>What this keeps real, and what it does not.</b> The mirror is a real {@code git clone
 * --mirror} of a real bare and every write is a real {@code git push} back into it: a real ref
 * negotiation, a real fast-forward check, a real refusal shape. What it replaces is only the
 * <em>transport</em> — there is no HTTP hop and no {@code ProtectedRefHook}, which is qits-githost's
 * and proven there.
 *
 * <p>Pair it with {@link FakeGitHostRepositories}, which operates on the exact same address.
 */
@ApplicationScoped
public class FakeGitHostAddress implements GitHostAddress {

  static final Path ROOT = Path.of("target", "qits-test-host");

  @Override
  public String fetchUrl(String repoId) {
    return ROOT.resolve(repoId + ".git").toAbsolutePath().toString();
  }

  /** One address, so reads and writes cannot drift apart — the same arrangement as production. */
  @Override
  public String pushUrl(String repoId) {
    return fetchUrl(repoId);
  }
}
