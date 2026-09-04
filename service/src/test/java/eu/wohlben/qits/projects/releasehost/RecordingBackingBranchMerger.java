package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.BackingBranchMerger;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link BackingBranchMerger}: records what was folded and answers what the test
 * scripted. An ordinary bean over the {@code @DefaultBean} HTTP adapter, so it wins the injection
 * simply by existing and no test reaches a git host; state through <b>methods</b>, the package
 * convention (the injected reference is a CDI client proxy).
 *
 * <p><b>Its default answers a fresh merged sha every call</b>, which is the interesting default: a
 * request that is created, or whose sources moved, gets new content and therefore a re-arm. The
 * cases worth staging — {@code unchanged}, a conflict, an unreachable host — are each scripted per
 * test.
 */
@ApplicationScoped
public class RecordingBackingBranchMerger implements BackingBranchMerger {

  /** One fold, exactly as the domain asked for it — the sources are the assertion worth making. */
  public record Fold(String repoId, String target, List<String> sources, String message) {}

  private final List<Fold> folds = Collections.synchronizedList(new ArrayList<>());

  /** Null means "a fresh merged sha", the default. */
  private final AtomicReference<Outcome> scripted = new AtomicReference<>();

  public List<Fold> folds() {
    return List.copyOf(folds);
  }

  /** The most recent fold, for a test that only cares about the last one. */
  public Fold lastFold() {
    return folds.get(folds.size() - 1);
  }

  /** Every fold of one target ref — the per-request assertion, since folds are per request. */
  public List<Fold> foldsOf(String target) {
    return folds().stream().filter(fold -> fold.target().equals(target)).toList();
  }

  public void answer(Outcome outcome) {
    scripted.set(outcome);
  }

  /** Back to the default: every fold produces new content. */
  public void answerFreshMerges() {
    scripted.set(null);
  }

  public void reset() {
    folds.clear();
    scripted.set(null);
  }

  @Override
  public Outcome merge(String repoId, String target, List<String> sources, String message) {
    folds.add(new Fold(repoId, target, List.copyOf(sources), message));
    Outcome outcome = scripted.get();
    return outcome != null ? outcome : Outcome.merged(freshSha(), List.copyOf(sources));
  }

  public static String freshSha() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
