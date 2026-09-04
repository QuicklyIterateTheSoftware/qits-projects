package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ReleaseExecutor}: records what a READY request asked for and answers what the
 * test scripted. An ordinary bean over the {@code @DefaultBean} {@link GitHostReleaseExecutor}, so
 * it wins the injection simply by existing; state through methods, the package convention.
 *
 * <p><b>{@link #passThrough()} is the exception that makes the whole flow assertable.</b> Winning
 * the injection is what lets every state-machine test script an outcome without a git host — and it
 * is also what would leave the real executor reachable by nothing but a direct call, with {@code
 * ReleaseRequests}' half of the release (the pending-merge row, RELEASED, the siblings' re-fold)
 * never proved to run behind it. So the fake can delegate: the real executor is injected by its own
 * type, past its {@code @DefaultBean}, and a test that wants the whole chain turns it on. The
 * recording still happens, so the ask is assertable either way.
 */
@ApplicationScoped
public class RecordingReleaseExecutor implements ReleaseExecutor {

  /** One release, exactly as the domain asked for it. */
  public record Released(
      String requestId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String expectedSha,
      String summary,
      String requester,
      List<String> namedSources,
      String defaultBranch) {}

  /** By its OWN type, past the {@code @DefaultBean} this bean is currently beating. */
  @Inject GitHostReleaseExecutor real;

  private final List<Released> calls = Collections.synchronizedList(new ArrayList<>());
  private final AtomicReference<Outcome> outcome =
      new AtomicReference<>(Outcome.released("2026.831.90000", "released-sha-0"));
  private final AtomicBoolean delegate = new AtomicBoolean(false);

  public List<Released> calls() {
    return List.copyOf(calls);
  }

  public void answer(Outcome value) {
    outcome.set(value);
  }

  /** Stop scripting: the shipped executor answers, against whatever git host the test staged. */
  public void passThrough() {
    delegate.set(true);
  }

  public void reset() {
    calls.clear();
    outcome.set(Outcome.released("2026.831.90000", "released-sha-0"));
    delegate.set(false);
  }

  @Override
  public Outcome release(Release release) {
    calls.add(
        new Released(
            release.requestId(),
            release.repoId(),
            release.projectId(),
            release.repoName(),
            release.backingBranch(),
            release.mergedSha(),
            release.summary(),
            release.requester(),
            release.namedSources(),
            release.defaultBranch()));
    return delegate.get() ? real.release(release) : outcome.get();
  }
}
