package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.MergeConflictDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The release-request state machine: N sources folded onto a backing branch, settled by quality
 * gates, executed when they pass. The reason the build-status ledger lives in this service — a
 * verdict arrives, the ledger row is written, and the matching requests resolve in the same
 * consumption.
 *
 * <h2>A request is an octopus merge</h2>
 *
 * <p>Its participants are the <b>named branches</b> on it ({@code main} is implied by every create,
 * and more are added later) plus the <b>implicit tag sources</b>: every released tag of the
 * repository not yet merged to {@code main}. The implicit set is what makes each release a superset
 * of the releases still in flight — a release is a tag and {@code main} is finalized only after the
 * deployment, so between those two moments a request that did not fold the tag in would be a step
 * backwards from what is already shipping.
 *
 * <p>{@link #remerge} folds them into {@code refs/heads/release/<id>} through {@link
 * BackingBranchMerger} and stores the tip as {@code mergedSha}. <b>That fold is the re-arm</b>:
 * a new tip invalidates the gates and puts the request back to PENDING, exactly as a new branch head
 * used to. Four things trigger it, and each is per-request — a shared trigger is never a shared
 * merge, and one request's fold never touches a sibling's backing branch:
 *
 * <ol>
 *   <li>the request being created, and a source being added to it;
 *   <li>a push touching any of its named branches ({@link #onBranchMoved}, off {@code
 *       SCMPublishCommit});
 *   <li>a sibling release JOINING the implicit set — every open request of that repository re-folds,
 *       and it is a real change;
 *   <li>a pending tag LEAVING the set on reaching {@code main} ({@link #onReleasedTagMerged}) —
 *       content-idempotent, so the fold usually answers {@code unchanged} and nothing is announced.
 * </ol>
 *
 * <p><b>{@code unchanged} is not a change.</b> The git host answers it when every head is already
 * contained in the target: same sha, no new commit. Nothing is re-armed and no {@code
 * ReleaseRequestChanged} is dispatched, which is what makes a trigger with no content behind it
 * free. A fold that cannot be made at all is {@code CONFLICTED} — no ref moved, the conflict stored
 * for a person to act on, and <b>no event</b>; the next fold that succeeds clears it and dispatches.
 *
 * <h2>The build gate</h2>
 *
 * <p>A PENDING request becomes READY when three things hold for its {@code mergedSha}, in this
 * order:
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
 * <p>A request with no {@code mergedSha} yet is gated on nothing and stays PENDING: the fold has not
 * been computed, so there is no content to have an opinion about.
 *
 * <p><b>Execution happens off every other thread.</b> A READY request is handed to the one-thread
 * {@code release-request-worker}: the resolution runs under the bus consumption and the sweep on a
 * scheduler thread, and the door call is an HTTP round trip neither may sit on. The worker re-reads
 * the row, so a request executed twice over is settled by the first arrival — and the door's own
 * ALREADY_INTEGRATED refusal is the backstop behind that.
 */
@ApplicationScoped
public class ReleaseRequests {

  private static final Logger LOG = Logger.getLogger(ReleaseRequests.class);

  /** The fallback default branch, for a repository row that names none. */
  private static final String DEFAULT_MAIN = "main";

  @Inject ReleaseRequestRepository requests;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject BuildStatusLedger ledger;

  @Inject ObjectMapper json;

  @Inject Instance<ActiveBuilds> activeBuilds;

  @Inject Instance<ReleaseExecutor> executors;

  @Inject Instance<BackingBranchMerger> mergers;

  @Inject Instance<ReleaseRequestAnnouncer> announcers;

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

  // ---------------------------------------------------------------------------------------------
  // The caller-facing arms
  // ---------------------------------------------------------------------------------------------

  /**
   * Create (or converge on) the request the named branch participates in. The fresh request's named
   * sources are the repository's default branch and {@code branch} — {@code main} is <b>implied</b>
   * rather than asked for, because a release that does not contain what is already on main is not a
   * release anybody wants; naming the default branch itself simply makes a main-only request.
   *
   * <p>At most one open request per named branch, the merge-request shape kept: asking again for a
   * branch that already participates in an open request answers that request rather than opening a
   * second one, and adds nothing to it.
   */
  public ReleaseRequestDto request(String repoId, String branch, String summary, String requester) {
    String named = requireBranch(branch);
    if (summary == null || summary.isBlank()) {
      throw new BadRequestException("A release request carries a summary");
    }
    Repository repository =
        repositories
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    String projectId = repository.project == null ? null : repository.project.id;
    String repoName = names.nameFor(repository).orElse(null);
    String main =
        repository.mainBranch == null || repository.mainBranch.isBlank()
            ? DEFAULT_MAIN
            : repository.mainBranch;

    String id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest open = requests.findOpenByBranch(repoId, named).orElse(null);
                  if (open != null) {
                    open.summary = summary.trim();
                    if (requester != null) {
                      open.requester = requester;
                    }
                    open.updatedAt = Instant.now();
                    return open.id;
                  }
                  ReleaseRequest fresh = new ReleaseRequest();
                  fresh.id = UUID.randomUUID().toString();
                  fresh.repoId = repoId;
                  fresh.projectId = projectId;
                  fresh.repoName = repoName;
                  fresh.summary = summary.trim();
                  fresh.requester = requester;
                  fresh.state = ReleaseRequest.State.PENDING;
                  fresh.createdAt = Instant.now();
                  fresh.armedAt = fresh.createdAt;
                  fresh.updatedAt = fresh.createdAt;
                  requests.persist(fresh);
                  // main first: it is the head the fold starts from, and the order sources are
                  // added in is the order they become parents.
                  addSourceRow(fresh.id, main, null);
                  if (!main.equals(named)) {
                    addSourceRow(fresh.id, named, requester);
                  }
                  return fresh.id;
                });
    remerge(id, "created");
    return get(id);
  }

  /**
   * Put another branch on an open request. Idempotent — a branch already named is not added twice
   * and the request is answered unchanged — and a settled request refuses with a 409, because
   * widening what has already been released or withdrawn would rewrite a record.
   *
   * <p>Only <b>named</b> sources are caller-managed. The implicit tag sources are derived from what
   * the repository has in flight and an API that let a caller drop one would let somebody release a
   * step backwards from what is already shipping.
   */
  public ReleaseRequestDto addSource(String requestId, String branch, String actor) {
    String named = requireBranch(branch);
    boolean added =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requireOpenForChange(requestId);
                  if (sources
                      .find(row.id, ReleaseRequestSource.Kind.BRANCH, named)
                      .isPresent()) {
                    return false;
                  }
                  addSourceRow(row.id, named, actor);
                  row.updatedAt = Instant.now();
                  return true;
                });
    if (added) {
      remerge(requestId, "a source was added: " + named);
    }
    return get(requestId);
  }

  /**
   * Withdraw an open request: the ask is moot and a person (or the branch's deletion) said so.
   * WITHDRAWN is terminal and leaves its branches free — the next ask mints a fresh request rather
   * than reviving this one, and a push no longer re-merges it. A request already settled (RELEASED,
   * WITHDRAWN) refuses with a 409 naming its state: withdrawing what already concluded would rewrite
   * a record.
   */
  public ReleaseRequestDto withdraw(String id, String reason, String actor) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = requireOpenForChange(id);
              row.state = ReleaseRequest.State.WITHDRAWN;
              row.detail =
                  (reason == null || reason.isBlank())
                      ? "Withdrawn by " + (actor == null ? "an operator" : actor)
                      : reason.trim();
              row.retryable = false;
              row.updatedAt = Instant.now();
            });
    return get(id);
  }

  /** One request, as the API answers it. */
  public ReleaseRequestDto get(String id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row =
                  requests
                      .findByIdOptional(id)
                      .orElseThrow(
                          () -> new NotFoundException("Release request not found: " + id));
              return dto(row, row.repoName, sources.listByRequest(id), implicitFor(row.repoId));
            });
  }

  /** One repository's requests, newest first. */
  public List<ReleaseRequestDto> listByRepo(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleaseRequest> rows = requests.listByRepo(repoId);
              return decorate(rows, Map.of());
            });
  }

  /**
   * A whole project's requests, across every repository it owns, most recently moved first — the
   * one read that answers "what is waiting on me here" without walking the repositories.
   *
   * <p><b>Open by default, because that is the question.</b> With no {@code state} the answer is
   * {@link ReleaseRequestRepository#OPEN} — the requests that can still move. {@code all} answers
   * every state, and a state's own name narrows to it. A word naming none is a {@link
   * BadRequestException} rather than an empty list, so a typo in the filter never reads as "nothing
   * is pending" — the same posture the epic board's status filter takes.
   *
   * <p>Each row is named with the repository's <b>current</b> alias rather than the one recorded
   * when the request was made: a rename moves the name and leaves the row's snapshot behind, and a
   * list that spans repositories is exactly where a stale name would mislead. The map is one query
   * for the project, so naming the rows costs nothing per row. A repository with no alias keeps its
   * snapshot, and then null — the caller shows the id.
   */
  public List<ReleaseRequestDto> listByProject(String projectId, String state) {
    List<ReleaseRequest.State> states = statesFor(state);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> decorate(requests.listByProject(projectId, states), names.namesByRepository(projectId)));
  }

  // ---------------------------------------------------------------------------------------------
  // The triggers
  // ---------------------------------------------------------------------------------------------

  /**
   * A branch moved: every open request of the repository that names it re-folds, whatever state it
   * was in — a PENDING request re-gates the new fold, a READY-but-unexecuted one must not land the
   * old one, a REJECTED one comes back to life because the fix it asked for is exactly what a new
   * push is, a FAILED one retries against reality, and a CONFLICTED one is cleared by a push that
   * makes the fold succeed. Called from the {@code SCMPublishCommit} consumption; a branch no open
   * request names is a no-op.
   *
   * <p><b>Per request, never per branch.</b> A push to {@code main} participates in every open
   * request of the repository and each gets its own fold onto its own backing branch.
   */
  public void onBranchMoved(String repoId, String branch, String sha) {
    List<String> affected =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.findOpenByBranches(repoId, List.of(branch)).stream()
                        .map(row -> row.id)
                        .toList());
    for (String id : affected) {
      remerge(id, "a push moved " + branch + " to " + shortSha(sha));
    }
  }

  /**
   * A branch is gone, so it participates in nothing: it is dropped from every open request naming
   * it. A request left with nothing but the repository's default branch is <b>withdrawn</b> — there
   * is no work in it any more and the row would otherwise stand open forever (three did,
   * 2026-09-01) — and every other affected request simply re-folds without it.
   */
  public void onBranchDeleted(String repoId, String branch) {
    record Affected(String id, boolean withdrawn) {}
    List<Affected> affected =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Affected> touched = new ArrayList<>();
                  for (ReleaseRequest open : requests.findOpenByBranches(repoId, List.of(branch))) {
                    sources
                        .find(open.id, ReleaseRequestSource.Kind.BRANCH, branch)
                        .ifPresent(sources::delete);
                    List<ReleaseRequestSource> left = sources.listByRequest(open.id);
                    boolean empty =
                        left.isEmpty()
                            || left.stream().allMatch(s -> s.name.equals(mainOf(repoId)));
                    if (empty) {
                      open.state = ReleaseRequest.State.WITHDRAWN;
                      open.detail = "Withdrawn: the branch was deleted";
                      open.retryable = false;
                      open.updatedAt = Instant.now();
                      LOG.infof(
                          "Release request %s withdrawn: branch %s of %s was deleted",
                          open.id, branch, repoId);
                    } else {
                      open.updatedAt = Instant.now();
                    }
                    touched.add(new Affected(open.id, empty));
                  }
                  return touched;
                });
    for (Affected row : affected) {
      if (!row.withdrawn()) {
        remerge(row.id(), "the source branch " + branch + " was deleted");
      }
    }
  }

  /**
   * A released tag reached {@code main}, so it LEAVES the implicit source set — the post-deployment
   * merge's half of the bookkeeping, and the one the later task in this epic calls. Every open
   * request of the repository re-folds without it.
   *
   * <p><b>Content-idempotent, and that is the point.</b> A tag on {@code main} is already contained
   * in the fold through {@code main} itself, so dropping it changes nothing the git host can see: the
   * merge answers {@code unchanged}, no request is re-armed and no event is dispatched. A tag
   * nothing has a pending row for is a no-op.
   */
  public void onReleasedTagMerged(String repoId, String tagName) {
    boolean cleared =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .find(repoId, tagName)
                        .filter(row -> row.mergedAt == null)
                        .map(
                            row -> {
                              row.mergedAt = Instant.now();
                              return true;
                            })
                        .orElse(false));
    if (!cleared) {
      return;
    }
    remergeOpenOf(repoId, null, "the released tag " + tagName + " reached main");
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
   * "could not ask qits-ci" and "settle window not over yet" into delays instead of stalls, what
   * retries a FAILED execution — a <b>retryable</b> one only — and what re-folds a request whose
   * very first merge could not be made because the git host was unreachable.
   *
   * <p>A CONFLICTED request is deliberately <b>not</b> re-folded here. A conflict is a fact about
   * content that answers the same on every knock, and knocking anyway is the unbounded-loop defect
   * this file already paid for once (measured 2026-09-01). Something has to change first, and the
   * things that change it are all triggers of their own.
   */
  public void sweep() {
    List<ReleaseRequest> open =
        QuarkusTransaction.requiringNew().call(() -> List.copyOf(requests.listOpen()));
    for (ReleaseRequest row : open) {
      if (row.state != ReleaseRequest.State.CONFLICTED && row.mergedSha == null) {
        remerge(row.id, "the first fold had not been made yet");
        continue;
      }
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

  // ---------------------------------------------------------------------------------------------
  // The fold
  // ---------------------------------------------------------------------------------------------

  /**
   * A fold that produced something new, carried out of its transaction so the announcement is made
   * after it — never inside, the rule {@code RepositoryRenamedAnnouncer} states. Null stands for
   * "nothing to announce", which is every other outcome.
   */
  private record Folded(
      String releaseRequestId,
      String projectId,
      String repoId,
      String repoName,
      String backingBranch,
      String mergedSha,
      Instant changedAt) {}

  /**
   * Fold one request's sources onto its backing branch, and act on what came back. Package-private
   * so the suite can drive a single fold without a trigger.
   *
   * <p>The git-host call is made <b>outside</b> every transaction, the shape the door call already
   * takes: the read that assembles the sources and the write that applies the answer are two short
   * transactions with an HTTP round trip between them.
   */
  void remerge(String id, String why) {
    record Ask(String repoId, List<String> refs, String summary, boolean stillOpen) {}
    Ask ask =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null || !ReleaseRequestRepository.OPEN.contains(row.state)) {
                    return new Ask(null, List.of(), null, false);
                  }
                  return new Ask(
                      row.repoId,
                      refsOf(sources.listByRequest(id), implicitFor(row.repoId)),
                      row.summary,
                      true);
                });
    if (!ask.stillOpen()) {
      return;
    }
    String target = "refs/heads/" + ReleaseRequest.backingBranchOf(id);
    if (!mergers.isResolvable()) {
      note(id, "No git host is configured; the sources for this request cannot be folded");
      return;
    }
    BackingBranchMerger.Outcome outcome;
    try {
      outcome =
          mergers
              .get()
              .merge(
                  ask.repoId(),
                  target,
                  ask.refs(),
                  "Release request " + id + ": " + ask.summary());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not lose the request.
      LOG.warnf(e, "The backing-branch merger threw for release request %s", id);
      outcome = BackingBranchMerger.Outcome.unreachable("merger error: " + e.getMessage());
    }
    Folded folded = apply(id, target, why, outcome);
    if (folded != null) {
      announce(folded);
    }
    if (outcome.folded()) {
      evaluate(id);
    }
  }

  /** The write half of a fold: the row is re-read, so a request settled mid-call is left alone. */
  private Folded apply(
      String id, String target, String why, BackingBranchMerger.Outcome outcome) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
              if (row == null || !ReleaseRequestRepository.OPEN.contains(row.state)) {
                return null;
              }
              Instant now = Instant.now();
              row.updatedAt = now;
              switch (outcome.result()) {
                case CONFLICT -> {
                  row.state = ReleaseRequest.State.CONFLICTED;
                  row.detail =
                      "The sources could not be folded ("
                          + why
                          + "): "
                          + outcome.conflicts().stream()
                              .map(BackingBranchMerger.Conflict::path)
                              .distinct()
                              .collect(Collectors.joining(", "));
                  row.conflictDetail = conflictJson(target, outcome.conflicts());
                  row.retryable = false;
                  return null;
                }
                case UNREACHABLE -> {
                  // A fact about the moment, not about the request: the state is left where it was
                  // and the sweep folds again.
                  row.detail = "The sources could not be folded: " + outcome.detail();
                  return null;
                }
                case UNCHANGED -> {
                  boolean wasConflicted = row.state == ReleaseRequest.State.CONFLICTED;
                  row.conflictDetail = null;
                  if (wasConflicted) {
                    // The fold is possible again and the tip is the answer, but it is the SAME tip
                    // — nothing new to build. Back to PENDING against what is already there, and
                    // the gate reads the verdicts that sha already has.
                    row.state = ReleaseRequest.State.PENDING;
                    row.detail = "The conflict is resolved; the fold is unchanged";
                    row.mergedSha = outcome.sha();
                  }
                  return null;
                }
                default -> {
                  boolean moved = !outcome.sha().equals(row.mergedSha);
                  row.mergedSha = outcome.sha();
                  row.conflictDetail = null;
                  if (!moved) {
                    return null;
                  }
                  rearm(row, why);
                  return new Folded(
                      row.id,
                      row.projectId,
                      row.repoId,
                      row.repoName,
                      row.backingBranch(),
                      row.mergedSha,
                      now);
                }
              }
            });
  }

  /** The one place a request changes sha: gates invalidated, PENDING again, the window restarted. */
  private static void rearm(ReleaseRequest open, String why) {
    open.state = ReleaseRequest.State.PENDING;
    open.detail = "Re-armed onto " + shortSha(open.mergedSha) + " (" + why + ")";
    open.version = null;
    open.retryable = false;
    open.armedAt = Instant.now();
    open.updatedAt = open.armedAt;
  }

  /** Fire and forget, outside every transaction and never able to fail a fold. */
  private void announce(Folded folded) {
    if (!announcers.isResolvable()) {
      return;
    }
    try {
      announcers
          .get()
          .onReleaseRequestChanged(
              folded.projectId(),
              folded.repoId(),
              folded.repoName(),
              folded.releaseRequestId(),
              folded.backingBranch(),
              folded.mergedSha(),
              folded.changedAt());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce the change to release request %s", folded.releaseRequestId());
    }
  }

  /** Re-fold every open request of a repository, optionally skipping one. */
  private void remergeOpenOf(String repoId, String skipId, String why) {
    List<String> ids =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.listOpenByRepo(repoId).stream()
                        .map(row -> row.id)
                        .filter(id -> !id.equals(skipId))
                        .toList());
    ids.forEach(id -> remerge(id, why));
  }

  // ---------------------------------------------------------------------------------------------
  // The gate and the execution
  // ---------------------------------------------------------------------------------------------

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
                  if (row.mergedSha == null) {
                    // Nothing has been folded yet: there is no content to have an opinion about.
                    return false;
                  }
                  List<CommitBuildStatusDto> verdicts =
                      ledger.verdictsOf(row.repoId, row.mergedSha);
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
                            + row.mergedSha;
                    row.updatedAt = Instant.now();
                    return false;
                  }
                  Integer active =
                      activeBuilds.isResolvable()
                          ? activeBuilds.get().activeFor(row.repoId, row.mergedSha).orElse(null)
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
   * sweep retries it is the executor's classification.
   *
   * <p>What is released is the <b>backing branch</b> at the <b>merged sha</b> — the fold, not any
   * one participant. The release becoming a tag alone, and {@code main} being finalized after the
   * deployment, are the later halves of this epic; what this arm owes them already is the
   * bookkeeping below, which records the released tag as pending a merge to main.
   */
  private void execute(String id) {
    ReleaseRequest snapshot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null
                      || row.mergedSha == null
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
                  snapshot.backingBranch(),
                  snapshot.mergedSha,
                  snapshot.summary,
                  snapshot.requester);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not kill the worker.
      LOG.warnf(e, "Release executor threw for request %s", id);
      outcome = ReleaseExecutor.Outcome.refused("executor error: " + e.getMessage());
    }
    if (outcome.released()) {
      // The tag is tracked BEFORE the request is settled, so that a reader who sees RELEASED can
      // rely on the release being in the in-flight set. Only the siblings' re-fold is left after.
      recordReleasedTag(snapshot.repoId, id, outcome.version(), snapshot.mergedSha);
      settle(id, ReleaseRequest.State.RELEASED, null, outcome.version(), false);
      LOG.infof(
          "Release request %s released %s@%s as %s",
          id,
          snapshot.repoName != null ? snapshot.repoName : snapshot.repoId,
          snapshot.backingBranch(),
          outcome.version());
      remergeOpenOf(
          snapshot.repoId, id, "the sibling release " + outcome.version() + " is in flight");
    } else {
      settle(id, ReleaseRequest.State.FAILED, outcome.detail(), null, outcome.retryable());
      LOG.warnf(
          "Release request %s was not released (%s): %s",
          id, outcome.retryable() ? "will retry" : "final until re-armed", outcome.detail());
    }
  }

  /**
   * A release landed: the tag it produced JOINS the repository's implicit source set until the
   * post-deployment merge puts it on {@code main}, and every <b>other</b> open request of that
   * repository re-folds so that it is a superset of the release now in flight.
   *
   * <p>Populated going forward only. A platform that released before this table existed has tags
   * with no row, which is the honest answer: nothing here can know whether they reached main.
   */
  private void recordReleasedTag(
      String repoId, String requestId, String version, String releasedSha) {
    if (version == null || version.isBlank()) {
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (pendingTags.find(repoId, version).isPresent()) {
                return;
              }
              ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
              row.id = UUID.randomUUID().toString();
              row.repoId = repoId;
              row.tagName = version;
              row.releasedSha = releasedSha;
              row.releaseRequestId = requestId;
              row.releasedAt = Instant.now();
              pendingTags.persist(row);
            });
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

  /** A sentence on an open request that changes nothing else — the unreachable-git-host arm. */
  private void note(String id, String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                requests
                    .findByIdOptional(id)
                    .filter(row -> ReleaseRequestRepository.OPEN.contains(row.state))
                    .ifPresent(
                        row -> {
                          row.detail = detail;
                          row.updatedAt = Instant.now();
                        }));
  }

  // ---------------------------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------------------------

  /** The word "all", spelled once — every other value is a state name or a mistake. */
  private static final String ALL_STATES = "all";

  /**
   * The states a {@code state} query means. Absent or blank is the open set; {@code all} is every
   * state (spelled as the full set rather than as "no filter", so one query shape serves both); a
   * state name, in any case, is itself.
   */
  private static List<ReleaseRequest.State> statesFor(String state) {
    if (state == null || state.isBlank()) {
      return ReleaseRequestRepository.OPEN;
    }
    String wanted = state.trim();
    if (ALL_STATES.equalsIgnoreCase(wanted)) {
      return List.of(ReleaseRequest.State.values());
    }
    for (ReleaseRequest.State candidate : ReleaseRequest.State.values()) {
      if (candidate.name().equalsIgnoreCase(wanted)) {
        return List.of(candidate);
      }
    }
    throw new BadRequestException(
        "Unknown release-request state: "
            + state
            + ". Name one of "
            + Arrays.stream(ReleaseRequest.State.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "))
            + ", or '"
            + ALL_STATES
            + "' for every state; leaving it off answers the open ones.");
  }

  /**
   * Names a list of rows without a query per row: one read of every named source in the page, and
   * one read of each distinct repository's pending tags.
   */
  private List<ReleaseRequestDto> decorate(
      List<ReleaseRequest> rows, Map<String, String> currentNames) {
    Map<String, List<ReleaseRequestSource>> named =
        sources.listByRequests(rows.stream().map(row -> row.id).toList()).stream()
            .collect(Collectors.groupingBy(source -> source.requestId));
    Map<String, List<ReleasedTagPendingMerge>> implicit =
        rows.stream()
            .map(row -> row.repoId)
            .distinct()
            .collect(Collectors.toMap(repoId -> repoId, this::implicitFor));
    return rows.stream()
        .map(
            row ->
                dto(
                    row,
                    currentNames.getOrDefault(row.repoId, row.repoName),
                    named.getOrDefault(row.id, List.of()),
                    implicit.getOrDefault(row.repoId, List.of())))
        .toList();
  }

  private List<ReleasedTagPendingMerge> implicitFor(String repoId) {
    return pendingTags.listPending(repoId);
  }

  private ReleaseRequestDto dto(
      ReleaseRequest row,
      String repoName,
      List<ReleaseRequestSource> named,
      List<ReleasedTagPendingMerge> implicit) {
    List<ReleaseRequestSourceDto> all = new ArrayList<>();
    for (ReleaseRequestSource source : named) {
      all.add(
          new ReleaseRequestSourceDto(
              source.kind.name(), source.name, "refs/heads/" + source.name, false));
    }
    for (ReleasedTagPendingMerge tag : implicit) {
      all.add(
          new ReleaseRequestSourceDto(
              ReleaseRequestSource.Kind.RELEASED_TAG.name(),
              tag.tagName,
              "refs/tags/" + tag.tagName,
              true));
    }
    return new ReleaseRequestDto(
        row.id,
        row.repoId,
        repoName,
        row.backingBranch(),
        List.copyOf(all),
        row.mergedSha,
        row.state.name(),
        row.summary,
        row.requester,
        row.detail,
        conflictOf(row),
        row.version,
        row.retryable,
        row.createdAt,
        row.updatedAt);
  }

  // ---------------------------------------------------------------------------------------------
  // Small things
  // ---------------------------------------------------------------------------------------------

  /**
   * The refs the git host is handed, in the order they become parents: the named branches as they
   * were added ({@code main} first), then the released tags still in flight, oldest first. Two
   * sources naming one commit are one head at the far side, so a duplicate costs nothing.
   */
  private static List<String> refsOf(
      List<ReleaseRequestSource> named, List<ReleasedTagPendingMerge> implicit) {
    Set<String> refs = new LinkedHashSet<>();
    named.forEach(source -> refs.add("refs/heads/" + source.name));
    implicit.forEach(tag -> refs.add("refs/tags/" + tag.tagName));
    return List.copyOf(refs);
  }

  private void addSourceRow(String requestId, String branch, String actor) {
    ReleaseRequestSource source = new ReleaseRequestSource();
    source.id = UUID.randomUUID().toString();
    source.requestId = requestId;
    source.kind = ReleaseRequestSource.Kind.BRANCH;
    source.name = branch;
    source.addedAt = Instant.now();
    source.addedBy = actor;
    sources.persist(source);
  }

  private ReleaseRequest requireOpenForChange(String id) {
    ReleaseRequest row =
        requests
            .findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Release request not found: " + id));
    if (row.state == ReleaseRequest.State.RELEASED
        || row.state == ReleaseRequest.State.WITHDRAWN) {
      throw new DomainException(409, "Release request " + id + " is already " + row.state);
    }
    return row;
  }

  private static String requireBranch(String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("A release request names a branch");
    }
    return branch.trim();
  }

  /** The repository's default branch, for the "nothing but main is left" reading. */
  private String mainOf(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(repository -> repository.mainBranch)
        .filter(branch -> branch != null && !branch.isBlank())
        .orElse(DEFAULT_MAIN);
  }

  private String conflictJson(String target, List<BackingBranchMerger.Conflict> conflicts) {
    try {
      return json.writeValueAsString(
          new MergeConflictDto(
              target,
              conflicts.stream()
                  .map(
                      c ->
                          new MergeConflictDto.ConflictedPath(
                              c.path(), c.head(), c.headSha(), c.reason()))
                  .toList()));
    } catch (Exception e) {
      // A conflict that cannot be written down is still a conflict: the state stands and the
      // detail sentence carries the paths.
      LOG.warnf("Could not record the conflict detail: %s", e.toString());
      return null;
    }
  }

  private MergeConflictDto conflictOf(ReleaseRequest row) {
    if (row.conflictDetail == null || row.conflictDetail.isBlank()) {
      return null;
    }
    try {
      return json.readValue(row.conflictDetail, MergeConflictDto.class);
    } catch (Exception e) {
      LOG.warnf("Release request %s has an unreadable conflict detail: %s", row.id, e.toString());
      return null;
    }
  }

  private static String shortSha(String sha) {
    if (sha == null) {
      return "(nothing)";
    }
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }
}
