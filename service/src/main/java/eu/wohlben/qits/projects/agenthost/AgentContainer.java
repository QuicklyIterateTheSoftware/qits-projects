package eu.wohlben.qits.projects.agenthost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A framework-free fluent builder for a project-agent container's {@code docker run} argv — a copy
 * of qits-workspaces' {@code WorkspaceContainer}. It accumulates the run parameters (name, user,
 * labels, host aliases, network, resource limits, env, volumes, image) and renders them, in a fixed
 * order, into the argv that follows {@code docker run}.
 *
 * <p>Callers do not construct this with the cross-cutting config; they take a pre-seeded instance
 * from {@link AgentContainerFactory}, which is what makes it structurally impossible to start a
 * project agent without the credential volume, the {@code qits.*} labels, the docker-host alias and
 * the host uid. {@link DockerAgentRuntime#run} prepends the runtime binary and {@code run} and
 * executes the result. Setter call order is irrelevant — {@link #toRunArgv()} always emits the same
 * order, which is what lets a test assert the argv as a list.
 */
public final class AgentContainer {

  private String name;
  private String user;
  private final Map<String, String> labels = new LinkedHashMap<>();
  private final List<String> addHosts = new ArrayList<>();
  private final Map<String, String> env = new LinkedHashMap<>();
  private final List<String[]> volumes = new ArrayList<>(); // {volumeName, mountPath}
  private String network;
  private String memory;
  private String pidsLimit;
  private String cpus;
  private String image;

  public AgentContainer name(String name) {
    this.name = name;
    return this;
  }

  public AgentContainer user(String user) {
    this.user = user;
    return this;
  }

  public AgentContainer label(String key, String value) {
    this.labels.put(key, value == null ? "" : value);
    return this;
  }

  /** Add a {@code --add-host=<hostSpec>} entry (e.g. {@code host.docker.internal:host-gateway}). */
  public AgentContainer addHost(String hostSpec) {
    this.addHosts.add(hostSpec);
    return this;
  }

  /** Set a container environment variable ({@code -e key=value}). */
  public AgentContainer env(String key, String value) {
    this.env.put(key, value == null ? "" : value);
    return this;
  }

  /** Mount {@code volumeName} at {@code mountPath} read/write ({@code -v volumeName:mountPath}). */
  public AgentContainer volume(String volumeName, String mountPath) {
    this.volumes.add(new String[] {volumeName, mountPath});
    return this;
  }

  /**
   * Attach the container to a user-defined Docker network ({@code --network <name>}) — the network
   * the daemon dials this service out over. A blank name adds nothing (the default bridge).
   */
  public AgentContainer network(String network) {
    this.network = network;
    return this;
  }

  /**
   * Cap the container's memory ({@code --memory <limit>} <em>and</em> {@code --memory-swap} at the
   * same value, so the container can neither exceed the cap nor swap-thrash the host past it). With
   * the cgroup limit in place every JVM inside sizes its default heap against it, so no per-tool
   * {@code -Xmx} plumbing is needed. Blank/null adds nothing (unlimited).
   */
  public AgentContainer memory(String limit) {
    this.memory = limit;
    return this;
  }

  /** Cap the container's process/thread count ({@code --pids-limit}). Blank/null adds nothing. */
  public AgentContainer pidsLimit(String pidsLimit) {
    this.pidsLimit = pidsLimit;
    return this;
  }

  /** Cap the container's CPU share ({@code --cpus}). Blank/null adds nothing. */
  public AgentContainer cpus(String cpus) {
    this.cpus = cpus;
    return this;
  }

  public AgentContainer image(String image) {
    this.image = image;
    return this;
  }

  /** The image this container runs, for logs and assertions. */
  public String image() {
    return image;
  }

  /**
   * The {@code docker run} argv <em>after</em> the runtime binary and the {@code run} verb: {@code
   * -d --init --name … --user … --label … --add-host=… --network … --memory … --memory-swap …
   * --pids-limit … --cpus … -e … -v … <image>}, in that fixed order.
   *
   * <p>No command follows the image. The container runs only {@code qits-projects-daemon}, via the
   * image ENTRYPOINT, and deliberately has no {@code sleep infinity} fallback: a container that
   * cannot run the daemon must fail to start rather than linger with this service's uid and mounts
   * and no control plane reaching it. {@code --init} puts tini at PID 1, so the daemon is tini's
   * child and signals and zombies are handled.
   */
  public List<String> toRunArgv() {
    List<String> argv = new ArrayList<>();
    argv.add("-d");
    argv.add("--init");
    if (name != null) {
      argv.add("--name");
      argv.add(name);
    }
    if (user != null) {
      argv.add("--user");
      argv.add(user);
    }
    for (Map.Entry<String, String> label : labels.entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    for (String host : addHosts) {
      argv.add("--add-host=" + host);
    }
    if (network != null && !network.isBlank()) {
      argv.add("--network");
      argv.add(network);
    }
    if (memory != null && !memory.isBlank()) {
      argv.add("--memory");
      argv.add(memory);
      // Same value, so the cap is hard: the container can't spill the difference into host swap.
      argv.add("--memory-swap");
      argv.add(memory);
    }
    if (pidsLimit != null && !pidsLimit.isBlank()) {
      argv.add("--pids-limit");
      argv.add(pidsLimit);
    }
    if (cpus != null && !cpus.isBlank()) {
      argv.add("--cpus");
      argv.add(cpus);
    }
    for (Map.Entry<String, String> variable : env.entrySet()) {
      argv.add("-e");
      argv.add(variable.getKey() + "=" + variable.getValue());
    }
    for (String[] volume : volumes) {
      argv.add("-v");
      argv.add(volume[0] + ":" + volume[1]);
    }
    if (image != null) {
      argv.add(image);
    }
    return argv;
  }
}
