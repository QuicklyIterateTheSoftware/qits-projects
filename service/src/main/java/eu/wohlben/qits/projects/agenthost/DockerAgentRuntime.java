package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.error.InternalServerErrorException;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link ContainerRuntime} backed by the {@code docker} CLI, shelled out via {@link ProcessBuilder}
 * — a copy of qits-workspaces' {@code DockerExecutor}, deliberately with no docker-java dependency
 * (this module compiles to a native image, and a shelled binary needs no registration). The runtime
 * binary is configurable so a rootless {@code podman} can be dropped in without code changes; the
 * argv shape below is the docker/podman common subset.
 *
 * <p><b>{@code @DefaultBean}.</b> It yields to any other bean of the type, which is what lets the
 * suite install a fake and never shell a container engine — the same arrangement {@code
 * ConfiguredGitHostAddress} has. Keep the annotation: dropping it makes the two an ambiguous
 * dependency and the build fails at {@code ArcProcessor#validate}, for every test at once.
 */
@ApplicationScoped
@DefaultBean
public class DockerAgentRuntime implements ContainerRuntime {

  private static final Logger LOG = Logger.getLogger(DockerAgentRuntime.class);

  @ConfigProperty(name = "qits.projects.agent-container-runtime", defaultValue = "docker")
  String runtime;

  /**
   * Assembles the {@code docker run} argv with the always-on cross-cutting config. This runtime
   * only prepends the binary and {@code run} and shells it out.
   */
  @Inject AgentContainerFactory containerFactory;

  /**
   * Create the shared volumes and the network once at startup, so they exist before any agent
   * container mounts or joins them — and so an operator can run the one-time agent login before the
   * first project agent is started. Best-effort: a missing or broken runtime just logs, which is
   * what keeps a docker-less host able to boot this service.
   *
   * <p><b>Packaged runs only</b> ({@link LaunchMode#NORMAL}), the {@code StartupSelfSeed} gate and
   * for a sharper reason than symmetry: a {@code @DefaultBean} that loses the injection contest to
   * a test fake <em>keeps its startup observer</em>, so without this line every {@code ./mvnw
   * verify} on a machine with docker would create real platform volumes and a real network. The
   * suite must touch no container engine at all.
   */
  void onStart(@Observes StartupEvent event) {
    if (!shouldEnsureSharedResources(LaunchMode.current())) {
      return;
    }
    ensureVolume(containerFactory.claudeVolume(), "agent credential (agent auth may be unavailable)");
    ensureVolume(containerFactory.mavenVolume(), "Maven repo cache");
    ensureVolume(containerFactory.pnpmVolume(), "pnpm store cache");
    ensureNetwork();
  }

  /** Packaged runs only — dev and test launch modes never shell a container engine. */
  static boolean shouldEnsureSharedResources(LaunchMode mode) {
    return mode == LaunchMode.NORMAL;
  }

  /** Idempotent {@code volume create}; no-op when the volume name is blank. */
  void ensureVolume(String volume, String purpose) {
    if (volume == null || volume.isBlank()) {
      return;
    }
    ExecResult result = runCapturing(List.of(runtime, "volume", "create", volume));
    if (result.exitCode() != 0) {
      LOG.warnf("Could not ensure shared %s volume '%s': %s", purpose, volume, result.output());
    }
  }

  /**
   * Ensure the shared network exists before any container joins it. Inspect-then-create so a
   * network already provisioned by compose (its usual owner) is left untouched, whatever driver it
   * has — the platform runs on a bridge today and on an overlay after the swarm re-bootstrap, and
   * both carry agent containers.
   *
   * <p>A <b>missing</b> network is created as an attachable overlay, not as the default bridge. A
   * swarm service cannot join a bridge, so rebuilding qits-net as one here would cut every platform
   * service off from the agent containers. An attachable overlay carries both.
   */
  void ensureNetwork() {
    String net = containerFactory.network();
    if (net == null || net.isBlank()) {
      return;
    }
    if (runCapturing(List.of(runtime, "network", "inspect", net)).exitCode() == 0) {
      return;
    }
    ExecResult overlay =
        runCapturing(List.of(runtime, "network", "create", "-d", "overlay", "--attachable", net));
    if (overlay.exitCode() == 0 || alreadyExists(overlay)) {
      return;
    }
    // A daemon that is not in a swarm has no overlay driver, which is the developer machine this
    // service also runs on. Fall back to the default bridge there — wrong under swarm, which is
    // why the overlay is tried first and this path says so in the log.
    LOG.warnf(
        "Could not create the agent network '%s' as an overlay, falling back to the default"
            + " driver: %s",
        net, overlay.output());
    ExecResult result = runCapturing(List.of(runtime, "network", "create", net));
    if (result.exitCode() != 0 && !alreadyExists(result)) {
      LOG.warnf(
          "Could not ensure the shared agent network '%s' (no daemon will reach qits-projects): %s",
          net, result.output());
    }
  }

  /**
   * Docker's answer when another process created the network between the inspect and the create.
   * The network is there, which is all this method wanted, so neither create tries again.
   */
  private static boolean alreadyExists(ExecResult result) {
    return result.output() != null && result.output().contains("already exists");
  }

  @Override
  public String containerName(String projectSlug) {
    return containerFactory.containerName(projectSlug);
  }

  @Override
  public Optional<ContainerInfo> inspect(String container) {
    ExecResult result =
        runCapturing(
            List.of(
                runtime,
                "container",
                "inspect",
                "-f",
                "{{.State.Running}}\t{{index .Config.Labels \"qits.project\"}}",
                container));
    if (result.exitCode() != 0) {
      return Optional.empty(); // no such container; a broken runtime reads the same, deliberately
    }
    String[] parts = result.output().trim().split("\t", -1);
    boolean running = parts.length > 0 && "true".equals(parts[0].trim());
    String projectId = parts.length > 1 ? parts[1].trim() : "";
    return Optional.of(new ContainerInfo(container, projectId, running));
  }

  @Override
  public String run(String projectId, String projectSlug, String repoName) {
    String name = containerFactory.containerName(projectSlug);
    // Create-if-absent the labelled per-project /workspace volume before the container mounts it,
    // so a recreation reattaches the same checkout instead of re-cloning it.
    ensureProjectVolume(projectId);
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.addAll(containerFactory.forProject(projectId, projectSlug, repoName).toRunArgv());

    ExecResult result = runCapturing(argv);
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start the agent container " + name + ": " + result.output());
    }
    return name;
  }

  @Override
  public void start(String container) {
    ExecResult result = runCapturing(List.of(runtime, "start", container));
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start the agent container " + container + ": " + result.output());
    }
  }

  @Override
  public void stop(String container) {
    ExecResult result = runCapturing(List.of(runtime, "stop", container));
    if (result.exitCode() != 0) {
      LOG.debugf("Failed to stop the agent container %s: %s", container, result.output());
    }
  }

  @Override
  public List<ContainerInfo> listAgentContainers() {
    ExecResult result =
        runCapturing(
            List.of(
                runtime,
                "ps",
                "-a",
                "--filter",
                "label=qits.managed=" + AgentContainerFactory.MANAGED_LABEL_VALUE,
                "--format",
                "{{.Names}}\t{{.Label \"qits.project\"}}\t{{.State}}"));
    if (result.exitCode() != 0) {
      LOG.warnf("Failed to list project-agent containers: %s", result.output());
      return List.of();
    }
    List<ContainerInfo> infos = new ArrayList<>();
    for (String line : result.output().split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.split("\t", -1);
      String name = parts.length > 0 ? parts[0] : "";
      String projectId = parts.length > 1 ? parts[1] : "";
      // `ps -a` lists stopped containers too (the ensure ladder needs them); {{.State}} is
      // "running" only when the container is actually up — a deliberate stop leaves it "exited".
      boolean running = parts.length > 2 && "running".equals(parts[2]);
      if (!name.isBlank()) {
        infos.add(new ContainerInfo(name, projectId, running));
      }
    }
    return infos;
  }

  @Override
  public String projectVolumeName(String projectId) {
    return containerFactory.projectVolumeName(projectId);
  }

  @Override
  public void ensureProjectVolume(String projectId) {
    String name = containerFactory.projectVolumeName(projectId);
    List<String> argv = new ArrayList<>(List.of(runtime, "volume", "create"));
    // Labels are set only at create time; docker ignores label changes on an existing volume.
    containerFactory
        .projectVolumeLabels(projectId)
        .forEach(
            (key, value) -> {
              argv.add("--label");
              argv.add(key + "=" + value);
            });
    argv.add(name);
    ExecResult result = runCapturing(argv);
    if (result.exitCode() != 0) {
      LOG.warnf("Could not ensure the project volume '%s': %s", name, result.output());
    }
  }

  /**
   * Runs the command capturing its combined output; never throws, always answers an exit code.
   *
   * <p>Package-private, and the single place this class starts a process, so a test can replace the
   * whole runtime by overriding one method.
   */
  ExecResult runCapturing(List<String> command) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    try {
      Process process = builder.start();
      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      return new ExecResult(process.waitFor(), output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ExecResult(-1, "interrupted");
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }
}
