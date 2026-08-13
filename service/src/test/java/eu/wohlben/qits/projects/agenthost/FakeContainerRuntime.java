package eu.wohlben.qits.projects.agenthost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The suite's {@link ContainerRuntime}: an in-memory place table and a log of the verbs the ladder
 * called on it. No docker, no orchestrator, no network — which is the point, because {@code ./mvnw
 * verify} has to be green from a clone of this repository alone.
 *
 * <p>It wins over {@code containershost/ContainersAgentRuntime} for free: that bean is
 * {@code @DefaultBean}, so any other bean of the type simply takes the injection. Nothing here is
 * annotated {@code @Alternative} and nothing needs to be.
 *
 * <p>{@link #calls} is what the ladder tests assert on. "Running, not running, absent" are three
 * different <em>verbs</em> — a touch, a restart, a run — and a test that only checked the answering
 * status would pass for a ladder that provisioned a container it should have brought back.
 *
 * <p><b>Places are keyed by project id, not by container name</b>, which is the seam's own change:
 * the orchestrator addresses {@code owner/workload/ref} and this service's ref is the project id.
 */
@ApplicationScoped
public class FakeContainerRuntime implements ContainerRuntime {

  /** Project id to its place, in insertion order so a listing is deterministic. */
  private final Map<String, ContainerInfo> places = new LinkedHashMap<>();

  /** Every verb this runtime was asked for, in order: {@code run:<name>}, {@code restart:<id>}, … */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  /** Volumes {@link #ensureProjectVolume} was asked to create, in order. */
  private final List<String> volumes = new CopyOnWriteArrayList<>();

  /** When set, the next {@link #run} throws it — the FAILED arm of the ladder. */
  private volatile RuntimeException runFailure;

  public void reset() {
    places.clear();
    calls.clear();
    volumes.clear();
    runFailure = null;
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  public List<String> volumes() {
    return List.copyOf(volumes);
  }

  public void failNextRun(RuntimeException failure) {
    this.runFailure = failure;
  }

  /** Put a place in the table without going through the ladder, to set a test's start state. */
  public void given(String projectId, String projectSlug, boolean running) {
    places.put(projectId, new ContainerInfo(containerName(projectSlug), running));
  }

  @Override
  public String containerName(String projectSlug) {
    return "qits-proj-" + projectSlug;
  }

  @Override
  public Optional<ContainerInfo> inspect(String projectId) {
    return Optional.ofNullable(places.get(projectId));
  }

  @Override
  public String run(String projectId, String projectSlug, String repoName) {
    ensureProjectVolume(projectId);
    calls.add("run:" + containerName(projectSlug) + ":" + repoName);
    if (runFailure != null) {
      RuntimeException failure = runFailure;
      runFailure = null;
      throw failure;
    }
    places.put(projectId, new ContainerInfo(containerName(projectSlug), true));
    return containerName(projectSlug);
  }

  @Override
  public String restart(String projectId, String projectSlug, String repoName) {
    calls.add("restart:" + projectId);
    places.put(projectId, new ContainerInfo(containerName(projectSlug), true));
    return containerName(projectSlug);
  }

  @Override
  public void stop(String projectId) {
    calls.add("stop:" + projectId);
    ContainerInfo existing = places.get(projectId);
    if (existing != null) {
      places.put(projectId, new ContainerInfo(existing.name(), false));
    }
  }

  @Override
  public void touch(String projectId) {
    calls.add("touch:" + projectId);
  }

  @Override
  public List<ContainerInfo> listAgentContainers() {
    return new ArrayList<>(places.values());
  }

  @Override
  public String projectVolumeName(String projectId) {
    return "qits_project_" + projectId;
  }

  @Override
  public void ensureProjectVolume(String projectId) {
    volumes.add(projectVolumeName(projectId));
  }
}
