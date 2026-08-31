package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ActiveBuilds;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ActiveBuilds}: an ordinary bean, so it wins the injection over the {@code
 * @DefaultBean} HTTP adapter. State is read and written through <b>methods</b> — the injected
 * reference is a CDI client proxy, the package convention.
 *
 * <p>Defaults to "zero active runs", which is the answer that lets a verdict-driven test move: the
 * interesting states (runs still active, could not ask) are staged per test.
 */
@ApplicationScoped
public class FakeActiveBuilds implements ActiveBuilds {

  private final AtomicReference<Optional<Integer>> answer =
      new AtomicReference<>(Optional.of(0));

  public void answer(Optional<Integer> value) {
    answer.set(value);
  }

  public void reset() {
    answer.set(Optional.of(0));
  }

  @Override
  public Optional<Integer> activeFor(String repoId, String commitSha) {
    return answer.get();
  }
}
