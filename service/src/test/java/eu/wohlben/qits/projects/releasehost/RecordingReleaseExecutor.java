package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ReleaseExecutor}: records what a READY request asked of the door and answers
 * what the test scripted. An ordinary bean over the {@code @DefaultBean} HTTP adapter; state
 * through methods, the package convention.
 */
@ApplicationScoped
public class RecordingReleaseExecutor implements ReleaseExecutor {

  public record Released(
      String repoId, String projectId, String repoName, String branch, String summary,
      String requester) {}

  private final List<Released> calls = Collections.synchronizedList(new ArrayList<>());
  private final AtomicReference<Outcome> outcome =
      new AtomicReference<>(Outcome.released("2026.831.90000"));

  public List<Released> calls() {
    return List.copyOf(calls);
  }

  public void answer(Outcome value) {
    outcome.set(value);
  }

  public void reset() {
    calls.clear();
    outcome.set(Outcome.released("2026.831.90000"));
  }

  @Override
  public Outcome release(
      String repoId, String projectId, String repoName, String branch, String summary,
      String requester) {
    calls.add(new Released(repoId, projectId, repoName, branch, summary, requester));
    return outcome.get();
  }
}
