package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.control.FeatureService;
import eu.wohlben.qits.epics.control.TaskService;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.gitmirror.AheadBehind;
import eu.wohlben.qits.projects.gitmirror.GitMirrorException;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.jboss.logging.Logger;

/**
 * The refinement lifecycle: find-or-create keyed by epic, the ensure ladder, stop, recreate,
 * discard, and the row projection the status strip reads. The projects-side replacement for what
 * the refining route used to take from qits-workspaces' {@code WorkspaceService}, narrowed to what
 * that route actually consumes.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li><b>Running</b> — no-op plus an idle-clock stamp; the process narration says so and settles.
 *   <li><b>Present but stopped</b> — wake: one {@code ensure} under {@code Recreate.ifChanged}, a
 *       start in place unless an image bump landed while it slept.
 *   <li><b>Absent</b> — provision: commission a credential, ensure a fresh container; the daemon
 *       self-clones the wrapper and its terminal {@code Provisioned}/{@code ProvisionFailed} is
 *       what settles the narration, routed by {@link RefinementDaemonRegistry}.
 * </ol>
 *
 * <p>The container work runs <b>off the request thread</b>: an ensure can sit behind an image pull,
 * and the browser gets the technical-process id to watch instead of a request that hangs. One lock
 * per row serializes the ladder; a second click while it is held answers the live process.
 *
 * <h2>The branch</h2>
 *
 * <p>{@code refining/<epicSlug>}, cut on the project's wrapper at its default branch — a refinement
 * always forks the wrapper's main, which is why the parent/child workspace tree and the integrate
 * door do not exist here. Adopt-existing is the create's ordinary path, not an error dance: a
 * branch already on the origin (a previous refinement of this epic, discarded row and all) is
 * adopted as it stands.
 */
@ApplicationScoped
public class RefinementService {

  private static final Logger LOG = Logger.getLogger(RefinementService.class);

  @Inject EpicService epics;
  @Inject FeatureService features;
  @Inject TaskService tasks;
  @Inject ProjectService projects;
  @Inject RepositoryService repositories;
  @Inject GitMirrorRegistry mirrors;
  @Inject RefinementRepository store;
  @Inject RefinementRuntime runtime;
  @Inject RefinementDaemonRegistry registry;
  @Inject RefinementTunnels tunnels;
  @Inject RefinementCommissions commissions;
  @Inject TechnicalProcessRegistry processes;
  @Inject RefinementChangePublisher changes;

  /**
   * One permit per refinement, so the ladder is serialized without serializing the service. A
   * {@link Semaphore} and deliberately not a lock: the permit is taken on the request thread and
   * released on the executor thread that finishes the work, and a lock's owner check would refuse
   * exactly that hand-over.
   */
  private final ConcurrentHashMap<Long, Semaphore> locks = new ConcurrentHashMap<>();

  /** The last ensure's failure, shown as {@code runtimeError} until the next attempt. */
  private final ConcurrentHashMap<Long, String> lastErrors = new ConcurrentHashMap<>();

  /** The live ensure/recreate narration per row, answered by the active-process read. */
  private final ConcurrentHashMap<Long, TechnicalProcess> activeProcesses =
      new ConcurrentHashMap<>();

  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "refinement-ensure");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  // ---- find or create ----------------------------------------------------------------------

  /**
   * The refinement of {@code epicId}, created if the epic has none. Creation cuts (or adopts)
   * {@code refining/<epicSlug>} on the project's wrapper and computes the chat preamble from the
   * epic tree; both need the epic, so an unknown id 404s here and nothing is half-made.
   */
  public Refinement findOrCreate(String epicId) {
    Optional<Refinement> existing =
        QuarkusTransaction.requiringNew().call(() -> store.findByEpic(epicId));
    if (existing.isPresent()) {
      return existing.get();
    }
    Epic epic = epics.get(epicId);
    if (epic.status != EpicStatus.REFINING) {
      throw new DomainException(
          409,
          "Epic " + epicId + " is " + epic.status + " — only a REFINING epic can be refined.");
    }
    Project project = projects.get(epic.projectId);
    Repository wrapper = wrapperOf(project);
    String branch = "refining/" + epic.slug;
    cutOrAdoptBranch(wrapper, branch);

    Refinement refinement = new Refinement();
    refinement.epicId = epic.id;
    refinement.projectId = project.id;
    refinement.repositoryId = wrapper.id;
    refinement.branch = branch;
    refinement.parent = wrapper.mainBranch == null ? "main" : wrapper.mainBranch;
    refinement.label = label(epic.slug);
    refinement.preamble = preamble(epic);
    refinement.createdAt = Instant.now();
    try {
      QuarkusTransaction.requiringNew().run(() -> store.persist(refinement));
    } catch (RuntimeException maybeRace) {
      // Two opens racing: the unique constraint on epic_id decides, and the loser adopts.
      Optional<Refinement> won =
          QuarkusTransaction.requiringNew().call(() -> store.findByEpic(epicId));
      if (won.isPresent()) {
        return won.get();
      }
      throw maybeRace;
    }
    LOG.infof(
        "Created refinement %s for epic %s on %s (%s)",
        refinement.id, epicId, ProjectService.wrapperName(project), branch);
    return refinement;
  }

  /** The refinement row, or 404. */
  public Refinement get(long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByIdOptional(id))
        .orElseThrow(() -> new NotFoundException("No refinement " + id));
  }

  /** The refinement of an epic, or empty — the read that never creates. */
  public Optional<Refinement> findByEpic(String epicId) {
    return QuarkusTransaction.requiringNew().call(() -> store.findByEpic(epicId));
  }

  /**
   * A project's refinements with their live halves and <b>no git drift</b> — the list redraws on
   * every activity hint, and a mirror refresh plus a merge preview per row is the single-row read's
   * price, not a listing's.
   */
  public List<RefinementView> listByProject(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> store.listByProject(projectId)).stream()
        .map(this::lightView)
        .toList();
  }

  private RefinementView lightView(Refinement refinement) {
    long id = refinement.id;
    Semaphore lock = locks.get(id);
    boolean provisioning = lock != null && lock.availablePermits() == 0;
    String runtimeStatus;
    String runtimeError = lastErrors.get(id);
    RefinementRuntime.ContainerInfo container = null;
    try {
      container = runtime.inspect(id).orElse(null);
    } catch (RuntimeException e) {
      runtimeError = e.getMessage();
    }
    if (provisioning) {
      runtimeStatus = "PROVISIONING";
    } else if (registry.provisionFailure(id).isPresent() || runtimeError != null) {
      runtimeStatus = "FAILED";
      runtimeError = registry.provisionFailure(id).orElse(runtimeError);
    } else if (container == null) {
      runtimeStatus = "STOPPED";
    } else {
      runtimeStatus = container.running() ? "RUNNING" : "STOPPED";
    }
    RefinementDaemonRegistry.DaemonInfo daemon = registry.lookup(id).orElse(null);
    return new RefinementView(
        refinement,
        runtimeStatus,
        runtimeError,
        "RUNNING".equals(runtimeStatus) ? registry.clean(id).orElse(null) : null,
        null,
        null,
        false,
        registry.agentActivity(id).orElse(null),
        daemon == null ? null : daemon.connectedAt(),
        daemon == null ? null : daemon.daemonVersion(),
        registry.daemonOutdated(id));
  }

  // ---- lifecycle ---------------------------------------------------------------------------

  /** Bring the container up (asynchronously) and answer the narration to watch. */
  public String ensureContainer(long id) {
    Refinement refinement = get(id);
    return begin(refinement, false);
  }

  /**
   * Replace the container: remove it (never its volume), then provision fresh — gated on a tree
   * the daemon has vouched is clean, because the writable layer dies with the container.
   */
  public String recreateContainer(long id) {
    Refinement refinement = get(id);
    if (!registry.clean(id).map(Boolean::booleanValue).orElse(false)) {
      throw new DomainException(
          400,
          "The working tree is not provably clean, so the container will not be replaced."
              + " Commit or discard the changes first — or if the container is not running,"
              + " simply start it.");
    }
    return begin(refinement, true);
  }

  /** Stop the container, leaving it and its checkout in place. Idempotent. */
  public Refinement stopContainer(long id) {
    Refinement refinement = get(id);
    runtime.stop(id);
    tunnels.closeTunnel(id);
    registry.forget(id);
    activeProcesses.remove(id);
    changes.fire(id, RefinementChangeHint.Topic.PROCESS);
    return refinement;
  }

  /**
   * The end of a refinement: container, volume, credential, branch, row — in that order, so a
   * failure leaves nothing orphaned ahead of it. The epic's ABANDONED transition is its own call
   * on the epics surface; this tears down only what this service hosts.
   */
  public void discard(long id) {
    Refinement refinement = get(id);
    tunnels.closeTunnel(id);
    registry.forget(id);
    activeProcesses.remove(id);
    runtime.delete(id);
    commissions.handBack(refinement);
    deleteBranchQuietly(refinement);
    QuarkusTransaction.requiringNew().run(() -> store.deleteById(id));
    lastErrors.remove(id);
    LOG.infof("Discarded refinement %s (epic %s)", id, refinement.epicId);
  }

  /** The id of the ensure narration currently live for this row, or null. */
  public String activeProcessId(long id) {
    TechnicalProcess process = activeProcesses.get(id);
    if (process == null) {
      return null;
    }
    if (process.isTerminal()) {
      activeProcesses.remove(id);
      return null;
    }
    return process.id();
  }

  private String begin(Refinement refinement, boolean replace) {
    long id = refinement.id;
    Semaphore lock = locks.computeIfAbsent(id, key -> new Semaphore(1));
    if (!lock.tryAcquire()) {
      // Somebody is already doing exactly this; watch their narration rather than queue a second
      // bring-up behind an image pull.
      String live = activeProcessId(id);
      if (live != null) {
        return live;
      }
      throw new DomainException(409, "The refinement container is already being worked on.");
    }
    try {
      TechnicalProcess process = processes.begin("ensure");
      activeProcesses.put(id, process);
      lastErrors.remove(id);
      registry.attachProvisionProcess(id, process);
      changes.fire(id, RefinementChangeHint.Topic.PROCESS);
      executor.submit(() -> run(refinement, replace, process, lock));
      return process.id();
    } catch (RuntimeException e) {
      lock.release();
      throw e;
    }
  }

  /** The ladder, off the request thread. The lock is held for the container verbs only. */
  private void run(Refinement refinement, boolean replace, TechnicalProcess process, Semaphore lock) {
    long id = refinement.id;
    try {
      // Off the request thread there is no ambient session, so the reads open their own.
      Project project =
          QuarkusTransaction.requiringNew().call(() -> projects.get(refinement.projectId));
      Repository wrapper =
          QuarkusTransaction.requiringNew().call(() -> repositories.get(refinement.repositoryId));
      String wrapperName = ProjectService.wrapperName(project);
      String epicSlug = epicSlugOf(refinement);
      process.openSegment("container");
      if (!branchStillExists(refinement)) {
        // The branch is gone from under the refinement — somebody resolved it out-of-band. The
        // container and its checkout are torn down rather than resurrecting a deleted branch; the
        // next open recreates the refinement from the epic.
        process.appendLine(
            "container",
            "The branch " + refinement.branch + " no longer exists on " + ProjectService.wrapperName(project) + ".");
        runtime.delete(id);
        commissions.handBack(refinement);
        QuarkusTransaction.requiringNew().run(() -> store.deleteById(id));
        process.appendLine("container", "The refinement was torn down.");
        process.settleSegment("container", false);
        process.failProvision("The refining branch is gone; open the epic again to start afresh.");
        return;
      }
      RefinementRuntime.ContainerInfo existing = runtime.inspect(id).orElse(null);
      if (replace && existing != null) {
        process.appendLine("container", "Removing the container (the checkout volume survives).");
        runtime.delete(id);
        existing = null;
      }
      if (existing == null) {
        process.appendLine("container", "Provisioning a fresh refinement container.");
        runtime.provision(refinement, project.slug, epicSlug, wrapperName);
        process.settleSegment("container", true);
        // Not settled here: the daemon's Provisioned/ProvisionFailed settles the narration, via
        // the registry. The idle reaper is the backstop for a daemon that never dials home.
      } else if (!existing.running()) {
        process.appendLine("container", "Waking the stopped container.");
        runtime.wake(refinement, project.slug, epicSlug, wrapperName);
        process.settleSegment("container", true);
      } else {
        runtime.touch(id);
        process.completeNoOp("container", "The container is already running.");
        process.finishProvision(true);
      }
    } catch (DomainException refused) {
      lastErrors.put(id, refused.getMessage());
      process.failProvision(refused.getMessage());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Could not ensure the refinement container %s", id);
      lastErrors.put(id, e.getMessage());
      process.failProvision(e.getMessage());
    } finally {
      lock.release();
      changes.fire(id, RefinementChangeHint.Topic.PROCESS);
    }
  }

  // ---- projection --------------------------------------------------------------------------

  /** The row as the status strip reads it. */
  public RefinementView view(Refinement refinement) {
    long id = refinement.id;
    Semaphore lock = locks.get(id);
    boolean provisioning = lock != null && lock.availablePermits() == 0;
    RefinementRuntime.ContainerInfo container = null;
    String runtimeError = lastErrors.get(id);
    try {
      container = runtime.inspect(id).orElse(null);
    } catch (RuntimeException e) {
      runtimeError = e.getMessage();
    }
    String runtimeStatus;
    if (provisioning) {
      runtimeStatus = "PROVISIONING";
    } else if (registry.provisionFailure(id).isPresent() || runtimeError != null) {
      runtimeStatus = "FAILED";
      runtimeError = registry.provisionFailure(id).orElse(runtimeError);
    } else if (container == null) {
      runtimeStatus = "STOPPED";
    } else {
      runtimeStatus = container.running() ? "RUNNING" : "STOPPED";
    }

    Boolean clean =
        "RUNNING".equals(runtimeStatus) ? registry.clean(id).orElse(null) : null;

    Integer ahead = null;
    Integer behind = null;
    boolean conflicts = false;
    try {
      RepoMirror mirror = mirrors.of(refinement.repositoryId);
      mirror.refresh();
      AheadBehind drift =
          mirror.aheadBehind(
              "refs/heads/" + refinement.parent, "refs/heads/" + refinement.branch);
      if (!AheadBehind.UNKNOWN.equals(drift)) {
        ahead = drift.ahead();
        behind = drift.behind();
        if (ahead != null && behind != null && ahead > 0 && behind > 0) {
          conflicts = wouldConflict(mirror, refinement);
        }
      }
    } catch (RuntimeException e) {
      LOG.debugf("Could not compute drift for refinement %s: %s", id, e.getMessage());
    }

    RefinementDaemonRegistry.DaemonInfo daemon = registry.lookup(id).orElse(null);
    return new RefinementView(
        refinement,
        runtimeStatus,
        runtimeError,
        clean,
        ahead,
        behind,
        conflicts,
        registry.agentActivity(id).orElse(null),
        daemon == null ? null : daemon.connectedAt(),
        daemon == null ? null : daemon.daemonVersion(),
        registry.daemonOutdated(id));
  }

  /** Everything the DTO is assembled from — the row plus the live halves. */
  public record RefinementView(
      Refinement refinement,
      String runtimeStatus,
      String runtimeError,
      Boolean clean,
      Integer ahead,
      Integer behind,
      boolean conflictsWithParent,
      String agentActivity,
      Instant daemonConnectedAt,
      String daemonVersion,
      Boolean daemonOutdated) {}

  // ---- the pieces --------------------------------------------------------------------------

  private Repository wrapperOf(Project project) {
    String wrapperName = ProjectService.wrapperName(project);
    return repositories
        .findByProjectAndName(project.id, wrapperName)
        .orElseThrow(
            () ->
                new DomainException(
                    409,
                    "Project "
                        + project.id
                        + " has no wrapper repository ("
                        + wrapperName
                        + "), so there is nothing to refine against."));
  }

  /** Cut the branch at the wrapper's default tip, or adopt one already on the origin. */
  private void cutOrAdoptBranch(Repository wrapper, String branch) {
    RepoMirror mirror = mirrors.of(wrapper.id);
    try {
      if (mirror.remoteBranchSha(branch).isPresent()) {
        return; // adopt as it stands — a previous refinement's work is work, not a conflict
      }
      mirror.refresh();
      String from = wrapper.mainBranch == null ? "main" : wrapper.mainBranch;
      PushOutcome outcome = mirror.createBranch(branch, from);
      if (!outcome.accepted() && mirror.remoteBranchSha(branch).isEmpty()) {
        throw new DomainException(
            502, "Could not cut " + branch + ": " + outcome.output());
      }
    } catch (GitMirrorException e) {
      throw new DomainException(
          502, "Could not reach the git host to cut " + branch + ": " + e.getMessage());
    }
  }

  private boolean branchStillExists(Refinement refinement) {
    try {
      return mirrors.of(refinement.repositoryId).remoteBranchSha(refinement.branch).isPresent();
    } catch (RuntimeException e) {
      // Could not ask is not "gone" — tearing a refinement down wants positive evidence.
      return true;
    }
  }

  private void deleteBranchQuietly(Refinement refinement) {
    try {
      mirrors.of(refinement.repositoryId).deleteBranch(refinement.branch);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not delete %s while discarding refinement %s: %s",
          refinement.branch, refinement.id, e.getMessage());
    }
  }

  private boolean wouldConflict(RepoMirror mirror, Refinement refinement) {
    try {
      return !mirror
          .previewMerge(
              "refs/heads/" + refinement.branch, "refs/heads/" + refinement.parent)
          .clean();
    } catch (RuntimeException e) {
      return false; // never a false warning
    }
  }

  private String epicSlugOf(Refinement refinement) {
    return refinement.branch.startsWith("refining/")
        ? refinement.branch.substring("refining/".length())
        : refinement.label;
  }

  /** {@code refining-<epicSlug>}, non-alphanumeric runs collapsed, 64 chars — the SPA's own rule. */
  static String label(String epicSlug) {
    String label = ("refining-" + epicSlug).replaceAll("[^A-Za-z0-9_-]+", "-");
    return label.length() <= 64 ? label : label.substring(0, 64);
  }

  /** The chat preamble, the same markdown the SPA used to build browser-side. */
  private String preamble(Epic epic) {
    StringBuilder text = new StringBuilder();
    text.append("# Refine: ").append(epic.title).append("\n\n");
    if (epic.description == null || epic.description.isBlank()) {
      text.append("_This draft has no description yet._\n");
    } else {
      text.append(epic.description).append("\n");
    }
    text.append("\n## Outline as it stands\n\n");
    List<Feature> outline = features.listByEpic(epic.id);
    if (outline.isEmpty()) {
      text.append("_No features drafted yet._\n");
      return text.toString();
    }
    for (Feature feature : outline) {
      text.append("- **").append(feature.title).append("**");
      if (feature.description != null && !feature.description.isBlank()) {
        text.append(" — ").append(feature.description);
      }
      text.append("\n");
      for (Task task : tasks.listByFeature(feature.id)) {
        text.append("  - ").append(task.title);
        if (task.description != null && !task.description.isBlank()) {
          text.append(" — ").append(task.description);
        }
        text.append("\n");
      }
    }
    return text.toString();
  }
}
