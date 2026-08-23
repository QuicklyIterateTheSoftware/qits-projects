package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The suite's {@link RefinementRuntime}: an in-memory place table and a verb log — no docker, no
 * orchestrator, no network. It wins over {@code containershost/ContainersRefinementRuntime} for
 * free, because that bean is {@code @DefaultBean}: the same arrangement
 * {@code agenthost/FakeContainerRuntime} has.
 */
@ApplicationScoped
public class FakeRefinementRuntime implements RefinementRuntime {

  private final Map<Long, ContainerInfo> places = new LinkedHashMap<>();

  /** Every verb, in order: {@code provision:<id>}, {@code wake:<id>}, {@code delete:<id>}, … */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  /**
   * A METHOD, not a public field, and that is load-bearing: the suite reaches this bean through a
   * client proxy, and a field read on a proxy answers the proxy's own (empty) field rather than the
   * contextual instance's.
   */
  public List<String> calls() {
    return calls;
  }

  @Override
  public synchronized Optional<ContainerInfo> inspect(long refinementId) {
    return Optional.ofNullable(places.get(refinementId));
  }

  @Override
  public synchronized void provision(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    calls.add("provision:" + refinement.id);
    places.put(
        refinement.id, new ContainerInfo("qits-ref-" + projectSlug + "-" + epicSlug, true));
  }

  @Override
  public synchronized void wake(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    calls.add("wake:" + refinement.id);
    places.put(
        refinement.id, new ContainerInfo("qits-ref-" + projectSlug + "-" + epicSlug, true));
  }

  @Override
  public synchronized void stop(long refinementId) {
    calls.add("stop:" + refinementId);
    ContainerInfo existing = places.get(refinementId);
    if (existing != null) {
      places.put(refinementId, new ContainerInfo(existing.containerName(), false));
    }
  }

  @Override
  public synchronized void touch(long refinementId) {
    calls.add("touch:" + refinementId);
  }

  @Override
  public synchronized void delete(long refinementId) {
    calls.add("delete:" + refinementId);
    places.remove(refinementId);
  }

  /** Test seam: put a place in a given state without going through a verb. */
  public synchronized void place(long refinementId, String name, boolean running) {
    places.put(refinementId, new ContainerInfo(name, running));
  }

  public synchronized void reset() {
    places.clear();
    calls.clear();
  }
}
