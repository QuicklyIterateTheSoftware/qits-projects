package eu.wohlben.qits.projects.agenthost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The suite's {@link ContainerRuntime}: an in-memory container table and a log of the verbs the
 * ladder called on it. No docker, no podman, no network — which is the point, because {@code
 * ./mvnw verify} has to be green from a clone of this repository alone.
 *
 * <p>It wins over {@link DockerAgentRuntime} for free: that bean is {@code @DefaultBean}, so any
 * other bean of the type simply takes the injection. Nothing here is annotated {@code @Alternative}
 * and nothing needs to be.
 *
 * <p>{@link #calls} is what the ladder tests assert on. "Running, stopped, absent" are three
 * different <em>verbs</em> — nothing, {@code start}, {@code run} — and a test that only checked the
 * answering status would pass for a ladder that re-ran a container it should have started.
 */
@ApplicationScoped
public class FakeContainerRuntime implements ContainerRuntime {

  /** Container name to its row, in insertion order so a listing is deterministic. */
  private final Map<String, ContainerInfo> containers = new LinkedHashMap<>();

  /** Every verb this runtime was asked for, in order: {@code run:<name>}, {@code start:<name>}, … */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  /** Volumes {@link #ensureProjectVolume} was asked to create, in order. */
  private final List<String> volumes = new CopyOnWriteArrayList<>();

  /** When set, the next {@link #run} throws it — the FAILED arm of the ladder. */
  private volatile RuntimeException runFailure;

  public void reset() {
    containers.clear();
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

  /** Put a container in the table without going through the ladder, to set a test's start state. */
  public void given(String name, String projectId, boolean running) {
    containers.put(name, new ContainerInfo(name, projectId, running));
  }

  @Override
  public String containerName(String projectSlug) {
    return "qits-proj-" + projectSlug;
  }

  @Override
  public Optional<ContainerInfo> inspect(String container) {
    return Optional.ofNullable(containers.get(container));
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
    String name = containerName(projectSlug);
    containers.put(name, new ContainerInfo(name, projectId, true));
    return name;
  }

  @Override
  public void start(String container) {
    calls.add("start:" + container);
    ContainerInfo existing = containers.get(container);
    if (existing != null) {
      containers.put(container, new ContainerInfo(container, existing.projectId(), true));
    }
  }

  @Override
  public void stop(String container) {
    calls.add("stop:" + container);
    ContainerInfo existing = containers.get(container);
    if (existing != null) {
      containers.put(container, new ContainerInfo(container, existing.projectId(), false));
    }
  }

  @Override
  public List<ContainerInfo> listAgentContainers() {
    return new ArrayList<>(containers.values());
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
