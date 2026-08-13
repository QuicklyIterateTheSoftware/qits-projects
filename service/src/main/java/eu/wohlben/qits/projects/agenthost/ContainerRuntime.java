package eu.wohlben.qits.projects.agenthost;

import java.util.List;
import java.util.Optional;

/**
 * The per-project agent container runtime — one container per project, holding a clone of that
 * project's wrapper repository under {@code /workspace} and running {@code qits-projects-daemon} as
 * its process.
 *
 * <p><b>This process holds no docker socket and spawns no container engine.</b> The sole
 * implementation is {@link eu.wohlben.qits.projects.containershost.ContainersAgentRuntime}, which
 * turns every method below into one HTTP call to qits-containers — the service that owns the daemon.
 * The interface survived the cutover because the ladder, the stop verb and the idle sweep are this
 * service's and did not change; what changed is how a container is <em>addressed</em>, and that is
 * the whole of the difference below.
 *
 * <h2>A place is addressed by project id, never by container name</h2>
 *
 * <p>The orchestrator's registry names a place {@code owner/workload/ref}, and this service's ref is
 * the <b>project id</b>. So {@link #inspect}, {@link #stop} and {@link #touch} take a project id
 * where they used to take a container name, and the name is what a person reads in {@code docker ps}
 * and nothing else. That is a strictly better identity than the one it replaces: the name is derived
 * from the project <em>slug</em>, which is unique only among <em>live</em> projects, so it used to
 * take a {@code qits.project} label to prove that a found container was really this project's. A row
 * keyed on the id proves it by construction.
 *
 * <p>What the label used to catch is still real and is caught in {@link #run}: a container left
 * behind by a <em>deleted</em> project still holds the name a new project taking the freed slug
 * would want.
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <p>qits-workspaces' interface carries {@code exec}, {@code execArgv}, {@code resolveTarget},
 * {@code rm}, {@code restart} and {@code removeWorkspaceVolume}. None of them has a caller here:
 *
 * <ul>
 *   <li><b>No {@code exec}.</b> Nothing on the host runs a command in a project agent — and nothing
 *       could: {@code exec} is not on the orchestrator's wire at all. The daemon owns every process
 *       in the container and is driven over its own API through the tunnel.
 *   <li><b>No {@code resolveTarget}.</b> {@code ProjectsApi} binds {@code 127.0.0.1} from capability
 *       1, so a project agent has no address on {@code qits-net} at all — there is no direct branch
 *       for the proxy to fall back to, only the reverse tunnel.
 *   <li><b>No volume removal.</b> Nothing here discards a checkout. The per-project
 *       {@code /workspace} volume outlives every verb on this interface, including {@link #restart},
 *       which is what makes the stop policy lossless.
 *   <li><b>No network creation.</b> {@code qits-net} is the bootstrap's, and the orchestrator only
 *       joins it. This interface used to carry an ensure-the-network startup observer, which was a
 *       second owner for a platform-wide fact.
 * </ul>
 */
public interface ContainerRuntime {

  /**
   * One place, as the orchestrator answers about it: the container's name and whether it is up.
   *
   * <p><b>There is no project id on it any more, and its absence is the point.</b> A read addressed
   * by project id needs no ownership proof in its answer, and the listing cannot supply one — the
   * orchestrator's envelopes carry no ref, so {@link #listAgentContainers} answers names and
   * {@link AgentIdleSweep} resolves them back to projects itself.
   *
   * <p>{@code running} is {@code true} for the one observed state that means the daemon is up.
   * Everything else — a stopped container, a row the orchestrator has not started yet, a container
   * that was removed under it — is {@code false}, because the ladder's answer to all of them is the
   * same and it is {@link #restart}.
   */
  record ContainerInfo(String name, boolean running) {}

  /** The deterministic container name for a project — the human hint, never the address. */
  String containerName(String projectSlug);

  /**
   * This project's place, or empty when the orchestrator holds no row for it.
   *
   * <p><b>Empty means "there is nothing there", never "we could not ask".</b> An orchestrator that
   * refused or did not answer throws, so the ladder reports a failure instead of provisioning a
   * second container against an answer nobody gave. That is the four-answer contract's whole point,
   * and it is where this differs from the docker runtime it replaces: a broken docker CLI used to
   * read exactly like an absent container.
   */
  Optional<ContainerInfo> inspect(String projectId);

  /**
   * Provision the project's container: ask the orchestrator to put one at this project's place, with
   * the per-project {@code /workspace} volume attached. Returns the container name. Throws on
   * failure — including a 409 when the name is held by a container another project's row still
   * names, which is a project that was deleted without its agent being removed.
   */
  String run(String projectId, String projectSlug, String repoName);

  /**
   * Bring a present-but-not-running container back up — the lossless half of the stop policy.
   *
   * <p><b>It is a start in place, and it keeps the container's docker id.</b> One {@code ensure}
   * does it: qits-containers sees a place whose spec is unchanged and whose container is stopped,
   * and starts the container the row already names rather than running a second one. So the
   * checkout, the submodules, any uncommitted work <em>and</em> everything the container wrote
   * outside its volumes all survive — which is the whole reason the stop policy stops rather than
   * removes.
   *
   * <p><b>It permits a replacement, and only a real spec change triggers one.</b> The request
   * carries {@code Recreate.ifChanged} ({@code AgentContainerFactory.forRestart}), so an agent-image
   * bump that landed while this agent was asleep is applied by replacing the container at wake. That
   * is the one moment a bump can be picked up without taking a container away from somebody working
   * in it — the running arm asks for no recreate at all. A replacement loses the writable layer and
   * nothing else, since every path this service cares about is a named volume and the daemon skips
   * its self-clone on an already-populated {@code /workspace}.
   *
   * <p><b>This used to be a forced re-create, and the reason is worth keeping.</b> qits-containers
   * had no start verb: an ensure of a stopped place under an unchanged spec fell through to a second
   * {@code docker run} under a name docker already held, so the row settled {@code MISSING} and the
   * caller was answered 200 about a container still sitting there in {@code exited}. This method
   * worked around it by making the spec differ on every call. That defect is fixed (qits-containers
   * 354fd7f, which added a bounded {@code start} to its driver seam and a real-daemon test that a
   * stop-then-ensure returns the same docker id), and the workaround is gone with it.
   */
  String restart(String projectId, String projectSlug, String repoName);

  /**
   * Gracefully stop the project's container <em>without</em> removing it, so a later
   * {@link #restart} reattaches the same checkout. Best-effort, never throws.
   */
  void stop(String projectId);

  /**
   * Tell the orchestrator this project's container is still wanted.
   *
   * <p>The spec carries an {@code IDLE_STOP} policy with this service's own idle window on it, so
   * the orchestrator runs the same sweep from its side. That belt is only a belt while the two
   * clocks agree: without this call it would stop a container a person is actively refining in,
   * because the orchestrator's clock is only ever stamped when a row is written. Best-effort and
   * never throws — a missed stamp costs a container that is stopped one window early and recreated
   * on the next ensure.
   */
  void touch(String projectId);

  /**
   * Every project-agent place this service owns, running or not.
   *
   * <p><b>From the orchestrator's rows, never from a label listing</b>, and scoped to this owner and
   * this workload — so two environments sharing one docker daemon cannot see each other's agents,
   * which is the constraint the label filter used to leave to an operator.
   */
  List<ContainerInfo> listAgentContainers();

  /** The deterministic per-project {@code /workspace} volume name (prefix + {@code projectId}). */
  String projectVolumeName(String projectId);

  /**
   * Create-if-absent the per-project {@code /workspace} volume, claimed by this owner; idempotent.
   * Best-effort — a failure just logs, because {@link #run}'s own spec names the volume and the
   * orchestrator creates it there too.
   *
   * <p>The volume is keyed on the project <b>id</b>, not the slug: a container name may be rebuilt
   * from a renameable-looking value, a checkout may not.
   */
  void ensureProjectVolume(String projectId);
}
