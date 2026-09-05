package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.ReleasedBranchWorkspaces;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A TEST-SCOPE implementation of the {@link ReleasedBranchWorkspaces} port that records what a
 * release asked for instead of dialling qits-workspaces.
 *
 * <p>{@link RecordingWorkspaceLifecycle}'s shape, and a plain {@code @ApplicationScoped} bean for
 * its reason: the port returns nothing, so no test needs to script an answer and there is nothing an
 * {@code @Alternative}/{@code @Priority} pair would buy. It beats {@code
 * workspacehost/HttpReleasedBranchWorkspaces} in the {@code service} suite simply by existing —
 * that one is {@code @DefaultBean} — so no release in any test reaches the network.
 *
 * <p>{@link #failWith} is the one piece of scripting: the port's contract is that an implementation
 * never throws, and the caller keeps a belt round it anyway. This is what lets the belt be an
 * assertion rather than a comment. Nothing in {@code src/main} references this class.
 */
@ApplicationScoped
public class RecordingReleasedBranchWorkspaces implements ReleasedBranchWorkspaces {

  /** One branch, exactly as the release named it. */
  public record Resolved(String repoId, String branch, String version, String releasedSha) {}

  private final List<Resolved> calls = new ArrayList<>();
  private final AtomicBoolean fail = new AtomicBoolean(false);

  @Override
  public synchronized void branchReleased(
      String repoId, String branch, String version, String releasedSha) {
    calls.add(new Resolved(repoId, branch, version, releasedSha));
    if (fail.get()) {
      throw new IllegalStateException("qits-workspaces exploded");
    }
  }

  public synchronized List<Resolved> calls() {
    return List.copyOf(calls);
  }

  /** Break the contract on purpose, so the caller's belt is proved rather than assumed. */
  public void failWith(boolean value) {
    fail.set(value);
  }

  public synchronized void reset() {
    calls.clear();
    fail.set(false);
  }
}
