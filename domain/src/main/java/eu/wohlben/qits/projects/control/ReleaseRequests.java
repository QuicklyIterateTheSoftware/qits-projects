package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The release-request state machine: created where the release door used to merge at once, settled
 * by quality gates, executed when they pass. The reason the build-status ledger lives in this
 * service — a verdict arrives, the ledger row is written, and the matching requests resolve in the
 * same consumption.
 *
 * <h2>The build gate</h2>
 *
 * <p>A PENDING request becomes READY when three things hold for its sha, in this order:
 *
 * <ol>
 *   <li><b>No gating verdict is red.</b> One red gating run is a REJECTED request, immediately —
 *       nothing to wait for. Non-gating verdicts (the userflow pipelines) are read and ignored.
 *   <li><b>No run is still queued or running.</b> The ledger cannot see those (only terminal runs
 *       announce), so {@link ActiveBuilds} asks qits-ci; an answer that cannot be had keeps the
 *       request pending rather than guessing — the sweep asks again.
 *   <li><b>Something vouches for the sha, or nothing ever will.</b> A gating SUCCESS is the vouch.
 *       With no verdict at all the request waits {@code qits.projects.release-requests.settle}
 *       from the sha's arming and then passes vacuously — the CI-less repository's path, with the
 *       window covering the race where the push's runs have not been accepted yet.
 * </ol>
 *
 * <p><b>Execution happens off every other thread.</b> A READY request is handed to the one-thread
 * {@code release-request-worker}: the resolution runs under the bus consumption and the sweep on a
 * scheduler thread, and the door call is an HTTP round trip neither may sit on. The worker re-reads
 * the row, so a request executed twice over is settled by the first arrival — and the door's own
 * ALREADY_INTEGRATED refusal is the backstop behind that.
 *
 * <p><b>A request tracks its branch</b> (the entity javadoc argues it): a new head re-arms the one
 * open request per branch — gates invalidated, PENDING again, same row — so what is gated is always
 * the sha on the row, and what lands is pinned to it.
 */
@ApplicationScoped
public class ReleaseRequests {

  private static final Logger LOG = Logger.getLogger(ReleaseRequests.class);

  @Inject ReleaseRequestRepository requests;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject BuildStatusLedger ledger;

  @Inject Instance<ActiveBuilds> activeBuilds;

  @Inject Instance<ReleaseExecutor> executors;

  /**
   * How long a request with no verdict at all waits after arming before passing vacuously — long enough that a
   * push's runs have been accepted and would show as active, short enough that a CI-less
   * repository's release is not meaningfully delayed.
   */
  @ConfigProperty(name = "qits.projects.release-requests.settle", defaultValue = "PT30S")
  Duration settle;

  private ExecutorService worker;

  @PostConstruct
  void start() {
    worker =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "release-request-worker");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  /**
   * Create (or converge on) the request for one branch — at most one open request per branch, the
   * merge-request shape. The same {@code (branch, sha)} answers the open request rather than a
   * duplicate; a different sha <b>re-arms</b> the open request onto the new head (gates
   * invalidated, back to PENDING, same row). Evaluated once inline, so a sha the ledger already
   * vouches for answers READY (and is handed to the worker) immediately.
   */
  public ReleaseRequestDto request(
      String repoId, String branch, String commitSha, String summary, String requester) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("A release request names a branch");
    }
    if (commitSha == null || !commitSha.matches("[0-9a-f]{7,64}")) {
      throw new BadRequestException(
          "A release request is about a commit: pass the branch head's sha");
    }
    if (summary == null || summary.isBlank()) {
      throw new BadRequestException("A release request carries a summary");
    }
    Repository repository =
        repositories
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    String projectId = repository.project == null ? null : repository.project.id;
    String repoName = names.nameFor(repository).orElse(null);

    ReleaseRequest row =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest open = requests.findOpenByBranch(repoId, branch).orElse(null);
                  if (open != null) {
                    if (!open.commitSha.equals(commitSha)) {
                      rearm(open, commitSha, "re-armed by a new request naming " + commitSha);
                    }
                    open.summary = summary.trim();
                    if (requester != null) {
                      open.requester = requester;
                    }
                    open.updatedAt = Instant.now();
                    return open;
                  }
                  ReleaseRequest fresh = new ReleaseRequest();
                  fresh.id = UUID.randomUUID().toString();
                  fresh.repoId = repoId;
                  fresh.projectId = projectId;
                  fresh.repoName = repoName;
                  fresh.branch = branch;
                  fresh.commitSha = commitSha;
                  fresh.summary = summary.trim();
                  fresh.requester = requester;
                  fresh.state = ReleaseRequest.State.PENDING;
                  fresh.createdAt = Instant.now();
                  fresh.armedAt = fresh.createdAt;
                  fresh.updatedAt = fresh.createdAt;
                  requests.persist(fresh);
                  return fresh;
                });
    evaluate(row.id);
    return dto(requireRequest(row.id));
  }

  /**
   * The branch's head moved: re-arm the open request onto the new head, whatever state it was in —
   * a PENDING request re-gates the new sha, a READY-but-unexecuted one must not land the old one,
   * a REJECTED one comes back to life because the fix it asked for is exactly what a new push is,
   * and a FAILED one retries against reality. Called from the SCMPublishCommit consumption; a
   * branch with no open request is a no-op.
   */
  public void onBranchMoved(String repoId, String branch, String sha) {
    String rearmed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest open = requests.findOpenByBranch(repoId, branch).orElse(null);
                  if (open == null || open.commitSha.equals(sha)) {
                    return null;
                  }
                  rearm(open, sha, "re-armed by a push moving the branch to " + sha);
                  return open.id;
                });
    if (rearmed != null) {
      evaluate(rearmed);
    }
  }

  /** The one place a request changes sha: gates invalidated, PENDING again, the window restarted. */
  private static void rearm(ReleaseRequest open, String sha, String note) {
    open.commitSha = sha;
    open.state = ReleaseRequest.State.PENDING;
    open.detail = note;
    open.version = null;
    open.retryable = false;
    open.armedAt = Instant.now();
    open.updatedAt = open.armedAt;
  }

  /**
   * Withdraw an open request: the ask is moot and a person (or the branch's deletion) said so.
   * WITHDRAWN is terminal and leaves the branch free — the next door call mints a fresh request
   * rather than reviving this one, and a moving head no longer re-arms it. A request already
   * settled (RELEASED, WITHDRAWN) refuses with a 409 naming its state: withdrawing what already
   * concluded would rewrite a record.
   */
  public ReleaseRequestDto withdraw(String id, String reason, String actor) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row =
                  requests
                      .findByIdOptional(id)
                      .orElseThrow(
                          () -> new NotFoundException("Release request not found: " + id));
              if (row.state == ReleaseRequest.State.RELEASED
                  || row.state == ReleaseRequest.State.WITHDRAWN) {
                throw new DomainException(
                    409, "Release request " + id + " is already " + row.state);
              }
              row.state = ReleaseRequest.State.WITHDRAWN;
              row.detail =
                  (reason == null || reason.isBlank())
                      ? "Withdrawn by " + (actor == null ? "an operator" : actor)
                      : reason.trim();
              row.retryable = false;
              row.updatedAt = Instant.now();
              return dto(row);
            });
  }

  /**
   * The branch is gone, so the open request tracking it is moot: nothing can gate or land a
   * deleted branch, and the row would otherwise stand open forever. Called from the
   * SCMDeleteBranch consumption; a branch with no open request is a no-op.
   */
  public void onBranchDeleted(String repoId, String branch) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                requests
                    .findOpenByBranch(repoId, branch)
                    .ifPresent(
                        open -> {
                          open.state = ReleaseRequest.State.WITHDRAWN;
                          open.detail = "Withdrawn: the branch was deleted";
                          open.retryable = false;
                          open.updatedAt = Instant.now();
                          LOG.infof(
                              "Release request %s withdrawn: branch %s of %s was deleted",
                              open.id, branch, repoId);
                        }));
  }

  /** One request, as the API answers it. */
  public ReleaseRequestDto get(String id) {
    return dto(requireRequest(id));
  }

  /** One repository's requests, newest first. */
  public List<ReleaseRequestDto> listByRepo(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> requests.listByRepo(repoId).stream().map(ReleaseRequests::dto).toList());
  }

  /**
   * A verdict landed for {@code (repoId, commitSha)} — re-evaluate what it may settle. Called by
   * the bus consumption right after the ledger write; the evaluation opens transactions of its own,
   * so the claim never spans this datasource.
   */
  public void onVerdict(String repoId, String commitSha) {
    List<String> pending =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.findPendingByCommit(repoId, commitSha).stream()
                        .map(row -> row.id)
                        .toList());
    pending.forEach(this::evaluate);
  }

  /**
   * The safety net under the event-driven path: re-evaluates every open request. It is what turns
   * "could not ask qits-ci" and "settle window not over yet" into delays instead of stalls, and
   * what retries a FAILED execution — a <b>retryable</b> one only. A refusal about the ask itself
   * would answer the same on every knock (measured 2026-09-01: two doomed door calls every sweep,
   * forever), so it stands with its detail until a re-arm changes the ask.
   */
  public void sweep() {
    List<ReleaseRequest> open =
        QuarkusTransaction.requiringNew().call(() -> List.copyOf(requests.listOpen()));
    for (ReleaseRequest row : open) {
      switch (row.state) {
        case PENDING -> evaluate(row.id);
        case READY -> enqueueExecution(row.id);
        case FAILED -> {
          if (row.retryable) {
            enqueueExecution(row.id);
          }
        }
        default -> {}
      }
    }
  }

  /** Package-private so the suite can drive one evaluation without the worker or the sweep. */
  void evaluate(String id) {
    boolean ready =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null || row.state != ReleaseRequest.State.PENDING) {
                    return false;
                  }
                  List<CommitBuildStatusDto> verdicts =
                      ledger.verdictsOf(row.repoId, row.commitSha);
                  CommitBuildStatusDto redGating =
                      verdicts.stream()
                          .filter(v -> v.gating() && !"SUCCESS".equals(v.status()))
                          .findFirst()
                          .orElse(null);
                  if (redGating != null) {
                    row.state = ReleaseRequest.State.REJECTED;
                    row.detail =
                        "Gating run "
                            + redGating.runId()
                            + " finished "
                            + redGating.status()
                            + " for "
                            + row.commitSha;
                    row.updatedAt = Instant.now();
                    return false;
                  }
                  Integer active =
                      activeBuilds.isResolvable()
                          ? activeBuilds.get().activeFor(row.repoId, row.commitSha).orElse(null)
                          : null;
                  if (active == null) {
                    // Could not ask (or no probe configured): only the settle window may pass a
                    // sha nothing vouches for, and a vouched sha still waits for it — without the
                    // active answer, "no runs left" cannot be told from "runs still coming".
                    if (Instant.now().isBefore(row.armedAt.plus(settle))) {
                      return false;
                    }
                  } else if (active > 0) {
                    return false;
                  }
                  boolean vouched =
                      verdicts.stream().anyMatch(v -> v.gating() && "SUCCESS".equals(v.status()));
                  if (!vouched && Instant.now().isBefore(row.armedAt.plus(settle))) {
                    return false;
                  }
                  row.state = ReleaseRequest.State.READY;
                  row.detail =
                      vouched ? null : "No CI verdict for this commit; passed after the settle window";
                  row.updatedAt = Instant.now();
                  return true;
                });
    if (ready) {
      enqueueExecution(id);
    }
  }

  private void enqueueExecution(String id) {
    worker.submit(() -> execute(id));
  }

  /**
   * The door call, on the worker. The row is re-read first, so of two enqueues the second finds a
   * settled request and does nothing; a refusal is FAILED with the door's words, and whether the
   * sweep retries it is the executor's classification — a failure of the moment is, a refusal about
   * the ask ({@code ALREADY_INTEGRATED}, a vanished branch) stands for an operator to read, revived
   * by the next re-arm.
   */
  private void execute(String id) {
    ReleaseRequest snapshot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null
                      || (row.state != ReleaseRequest.State.READY
                          && row.state != ReleaseRequest.State.FAILED)) {
                    return null;
                  }
                  return row;
                });
    if (snapshot == null) {
      return;
    }
    if (!executors.isResolvable()) {
      settle(
          id,
          ReleaseRequest.State.FAILED,
          "No release executor is configured; the request stays and the sweep will retry",
          null,
          true);
      return;
    }
    ReleaseExecutor.Outcome outcome;
    try {
      outcome =
          executors
              .get()
              .release(
                  snapshot.repoId,
                  snapshot.projectId,
                  snapshot.repoName,
                  snapshot.branch,
                  snapshot.commitSha,
                  snapshot.summary,
                  snapshot.requester);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not kill the worker.
      LOG.warnf(e, "Release executor threw for request %s", id);
      outcome = ReleaseExecutor.Outcome.refused("executor error: " + e.getMessage());
    }
    if (outcome.released()) {
      settle(id, ReleaseRequest.State.RELEASED, null, outcome.version(), false);
      LOG.infof(
          "Release request %s released %s@%s as %s",
          id, snapshot.repoName != null ? snapshot.repoName : snapshot.repoId, snapshot.branch,
          outcome.version());
    } else {
      settle(id, ReleaseRequest.State.FAILED, outcome.detail(), null, outcome.retryable());
      LOG.warnf(
          "Release request %s was not released (%s): %s",
          id, outcome.retryable() ? "will retry" : "final until re-armed", outcome.detail());
    }
  }

  private void settle(
      String id, ReleaseRequest.State state, String detail, String version, boolean retryable) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                requests
                    .findByIdOptional(id)
                    .ifPresent(
                        row -> {
                          row.state = state;
                          row.detail = detail;
                          row.retryable = retryable;
                          if (version != null) {
                            row.version = version;
                          }
                          row.updatedAt = Instant.now();
                        }));
  }

  private ReleaseRequest requireRequest(String id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                requests
                    .findByIdOptional(id)
                    .orElseThrow(() -> new NotFoundException("Release request not found: " + id)));
  }

  private static ReleaseRequestDto dto(ReleaseRequest row) {
    return new ReleaseRequestDto(
        row.id,
        row.repoId,
        row.branch,
        row.commitSha,
        row.state.name(),
        row.summary,
        row.requester,
        row.detail,
        row.version,
        row.retryable,
        row.createdAt,
        row.updatedAt);
  }
}
