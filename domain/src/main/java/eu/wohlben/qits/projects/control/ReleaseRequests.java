package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.MergeConflictDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestCommitsDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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
 * for a person to act on, and <b>no event</b>; the next fold that succeeds clears it and dispatches,
 * <em>including</em> one answering {@code unchanged}, unless that sha already carries a gating
 * verdict the gate can read.
 *
 * <h2>The build gate</h2>
 *
 * <p><b>Exactly one thing meets it: a gating {@code BuildSuccessful} whose commit is this request's
 * CURRENT fold.</b> A PENDING request becomes READY when, for its {@code mergedSha}:
 *
 * <ol>
 *   <li><b>No gating verdict is red.</b> One red gating run is a REJECTED request, immediately —
 *       nothing to wait for. Non-gating verdicts (the userflow pipelines) are read and ignored.
 *   <li><b>A gating verdict is green.</b> That is the vouch and there is no second way to earn it.
 *       <b>No verdict is not a pass</b>: the request stays PENDING and the sweep asks again, for as
 *       long as it takes. A repository whose pipeline never materializes therefore cannot release,
 *       and that is what a release gate is <em>for</em>.
 *   <li><b>No run is still queued or running</b>, where qits-ci can be asked. The ledger cannot see
 *       those (only terminal runs announce), so {@link ActiveBuilds} asks; a second gating pipeline
 *       still grinding on the same fold could still come back red, and a positive count holds the
 *       request. An answer that cannot be had does <b>not</b> hold a vouched sha back — the green
 *       gating verdict is the gate, the probe only ever narrows it.
 * </ol>
 *
 * <p><b>There was a fourth arm and it was a hole.</b> A sha nothing vouched for used to pass
 * vacuously once a settle window had lapsed, on the theory that a repository with no CI must still
 * be releasable. What it actually did — QA runs are created over the bus and executed by one serial
 * runner, so the active-runs probe answers 0 while a run is still being accepted — was wave releases
 * through in under two minutes, before their QA runs had executed at all (measured 2026-09-04). The
 * window is gone, with the property that configured it.
 *
 * <p>A request with no {@code mergedSha} yet is gated on nothing and stays PENDING: the fold has not
 * been computed, so there is no content to have an opinion about.
 *
 * <p><b>The merged sha is the correlation key, in both directions.</b> Only a verdict naming the
 * request's <em>current</em> fold can flip it — one for a superseded merge matches no request and is
 * simply not read — and the ledger rows of superseded merges are kept rather than deleted, because
 * they are the record of what was built. That is what makes the gate safe without anybody stopping
 * the runs, which in turn is what lets the run cancellation a re-fold asks for be best effort: it
 * frees a build agent, it does not decide anything. See {@link #evaluate(String, String)} and {@link
 * QaRunCancellations}.
 *
 * <h2>The release</h2>
 *
 * <p><b>Execution happens off every other thread.</b> A READY request is handed to the one-thread
 * {@code release-request-worker}: the resolution runs under the bus consumption and the sweep on a
 * scheduler thread, and the release is several HTTP round trips neither may sit on. The worker
 * re-reads the row, so a request executed twice over is settled by the first arrival.
 *
 * <p>What {@link ReleaseExecutor} does is stamp a calver, rewrite the manifests at the fold, commit
 * them onto the backing branch, <b>tag</b> that commit, delete the branches the release consumed and
 * announce {@code SCMRelease}. There is no push to {@code main} in it: a release is a tag, and
 * {@code main} is finalized after the deployment. This class's own share of that is the bookkeeping
 * — {@link #recordReleasedTag} puts the new tag in the repository's implicit source set and re-folds
 * every <em>other</em> open request, so each of them is a superset of what is already shipping.
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

  /**
   * The mirror-backed git reader, for the one question this class answers out of a repository rather
   * than out of its own tables: what a fold brought in. Not an {@code Instance} — it is this
   * module's own bean and not a port, and the mirror it reads is cloned on first use.
   */
  @Inject CommitService commits;

  @Inject ObjectMapper json;

  @Inject Instance<ActiveBuilds> activeBuilds;

  @Inject Instance<ReleaseExecutor> executors;

  @Inject Instance<BackingBranchMerger> mergers;

  @Inject Instance<ReleaseRequestAnnouncer> announcers;

  @Inject Instance<QaRunCancellations> cancellations;

  @Inject Instance<ReleasedBranchWorkspaces> releasedBranchWorkspaces;

  /**
   * The publish phase, called on the worker the instant a release lands: a repository that declares
   * no deployment has nothing to wait for and its tag is merged to {@code main} there and then.
   *
   * <p>The reverse edge — {@link ReleaseFinalization} injects this class to clear a tag out of the
   * implicit source set — makes this a cycle between two {@code @ApplicationScoped} beans, which
   * CDI's client proxies resolve. It is the honest shape: a release and its finalization are two
   * halves of one lifecycle and each has to be able to start the other.
   */
  @Inject ReleaseFinalization finalization;

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
              return dto(
                  row,
                  row.repoName,
                  sources.listByRequest(id),
                  implicitFor(row.repoId),
                  pendingTags.findByRequest(id).orElse(null));
            });
  }

  /**
   * One repository's requests, newest first — <b>the open ones plus the last {@value
   * #RECENT_RELEASED} releases</b> when nobody names a state, and exactly what {@link #statesFor}
   * makes of the word when somebody does.
   *
   * <p>Ordered by {@code createdAt} because this list is the repository's own record, read as "what
   * has been asked for here" — the project-wide list is the worklist and orders by what moved last.
   * The two reads are merged in memory rather than in SQL, which is what lets the tail be a page:
   * the open set is bounded by the flow and the tail by {@value #RECENT_RELEASED}.
   */
  public List<ReleaseRequestDto> listByRepo(String repoId, String state) {
    Selection selection = statesFor(state);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleaseRequest> rows = new ArrayList<>(requests.listByRepo(repoId, selection.states()));
              if (selection.recentReleased()) {
                rows.addAll(requests.listRecentReleased(repoId, RECENT_RELEASED));
              }
              rows.sort(Comparator.comparing((ReleaseRequest row) -> row.createdAt).reversed());
              return decorate(rows, Map.of());
            });
  }

  /**
   * A whole project's requests, across every repository it owns, most recently moved first — the
   * one read that answers "what is waiting on me here" without walking the repositories.
   *
   * <p><b>Open by default, plus what has just landed.</b> With no {@code state} the answer is {@link
   * ReleaseRequestRepository#OPEN} — the requests that can still move — followed by the project's
   * last {@value #RECENT_RELEASED} releases, so that a release leaving the worklist does not also
   * leave the page. {@code all} answers every state, and a state's own name narrows to it. A word
   * naming none is a {@link BadRequestException} rather than an empty list, so a typo in the filter
   * never reads as "nothing is pending" — the same posture the epic board's status filter takes.
   *
   * <p>Each row is named with the repository's <b>current</b> alias rather than the one recorded
   * when the request was made: a rename moves the name and leaves the row's snapshot behind, and a
   * list that spans repositories is exactly where a stale name would mislead. The map is one query
   * for the project, so naming the rows costs nothing per row. A repository with no alias keeps its
   * snapshot, and then null — the caller shows the id.
   */
  public List<ReleaseRequestDto> listByProject(String projectId, String state) {
    Selection selection = statesFor(state);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleaseRequest> rows =
                  new ArrayList<>(requests.listByProject(projectId, selection.states()));
              if (selection.recentReleased()) {
                rows.addAll(requests.listRecentReleasedByProject(projectId, RECENT_RELEASED));
              }
              rows.sort(Comparator.comparing((ReleaseRequest row) -> row.updatedAt).reversed());
              return decorate(rows, names.namesByRepository(projectId));
            });
  }

  /**
   * What this request's fold brought in — the commits of {@code mergedSha^1..mergedSha}, which is
   * the octopus's own range and therefore exactly what the participants contributed over the branch
   * it was folded onto.
   *
   * <p>Scoped by repository as well as by id, so a request read through the wrong repository's route
   * is a 404 rather than somebody else's answer. (The bare {@link #get} is deliberately left as it
   * is: it is the machine peers' read and has always been keyed on the id alone.)
   *
   * <p><b>Three empties, three sentences, and no error among them.</b> A request that has not been
   * folded yet has nothing to list; a fold the repository no longer holds — a withdrawn request's
   * backing branch is deleted, and history predating the mirror was never there — is a fact about
   * the repository and not a failure of this read; and a fold that added nothing over its target is
   * a real, if unusual, release. Every one of them answers 200 with the reason on {@code detail}.
   */
  public ReleaseRequestCommitsDto mergedCommits(String repoId, String requestId) {
    ReleaseRequest row =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests
                        .findByIdOptional(requestId)
                        .filter(candidate -> candidate.repoId.equals(repoId))
                        .orElseThrow(
                            () ->
                                new NotFoundException(
                                    "Release request not found: " + requestId)));
    if (row.mergedSha == null) {
      return new ReleaseRequestCommitsDto(null, List.of(), "Nothing has been folded yet");
    }
    CommitService.MergeRange range = commits.listMergeRange(repoId, row.mergedSha);
    if (!range.present()) {
      return new ReleaseRequestCommitsDto(
          row.mergedSha, List.of(), "The fold is no longer in the repository's history");
    }
    if (range.commits().isEmpty()) {
      return new ReleaseRequestCommitsDto(
          row.mergedSha,
          List.of(),
          "The fold brought nothing in: every source was already contained in what it was folded"
              + " onto");
    }
    return new ReleaseRequestCommitsDto(row.mergedSha, range.commits(), null);
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
   * merge's half of the bookkeeping, called by {@link ReleaseFinalization} once the git host has
   * applied that merge. Every open request of the repository re-folds without it.
   *
   * <p><b>This is the only writer of {@code merged_at}</b>, which is what keeps "the tag is on
   * {@code main}" and "the open requests no longer fold it in" one step rather than two.
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
   *
   * <p><b>The commit is a MERGED sha</b>, which is what makes this read a filter rather than a fan-
   * out: {@code findPendingByCommit} matches requests whose current fold <em>is</em> that commit, so
   * a verdict for a superseded merge names no request and settles nothing. {@link #evaluate(String,
   * String)} re-checks the same equality inside its transaction, because the fold can move between
   * the two.
   */
  public void onVerdict(String repoId, String commitSha) {
    List<String> pending =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.findPendingByCommit(repoId, commitSha).stream()
                        .map(row -> row.id)
                        .toList());
    pending.forEach(id -> evaluate(id, commitSha));
  }

  /**
   * The safety net under the event-driven path: re-evaluates every open request. It is what turns
   * "could not ask qits-ci" and "the verdict has not landed yet" into delays instead of stalls, what
   * retries a FAILED execution — a <b>retryable</b> one only — and what re-folds a request whose
   * very first merge could not be made because the git host was unreachable.
   *
   * <p><b>It is also the only thing that will ever release a request whose verdict arrived while
   * this service was down.</b> The gate now passes on a verdict and nothing else, so a missed
   * consumption is a request that sits PENDING until something asks again — which is this.
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
      String supersededSha,
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
      if (folded.supersededSha() != null) {
        // A fold that REPLACED a sha, not the first one: whatever qits-ci is still running for this
        // request is grinding on content nobody will accept. See cancel() for why this is best
        // effort and why it is scoped to this request alone.
        cancel(folded.repoId(), folded.releaseRequestId(), folded.supersededSha());
      }
      announce(folded);
    }
    if (outcome.folded()) {
      evaluate(id);
    }
  }

  /**
   * Ask qits-ci to stop this request's in-flight runs, because the fold they were started for has
   * been superseded.
   *
   * <p><b>Best effort, and never able to fail a fold.</b> The gate is correlated by sha — a verdict
   * naming a merge this request has already moved past matches nothing and settles nothing — so the
   * cancellation buys a build agent rather than correctness. An unreachable qits-ci, a refusal and
   * no implementation at all are one answer: carry on. That is also why it is called <b>after</b>
   * the fold's write transaction and outside every transaction, beside the announcement.
   *
   * <p><b>Scoped to this request and never to the repository.</b> A sibling request folds its own
   * sources onto its own backing branch and its runs are none of this one's business; cancelling by
   * repository would take a neighbour's green build away seconds before it settled them.
   */
  private void cancel(String repoId, String requestId, String supersededSha) {
    if (!cancellations.isResolvable()) {
      return;
    }
    try {
      LOG.debugf(
          "Release request %s superseded %s; asking qits-ci to cancel its runs",
          requestId, shortSha(supersededSha));
      cancellations.get().cancelRunsOf(repoId, requestId);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not cost the fold.
      LOG.warnf(e, "Could not ask qits-ci to cancel the runs of release request %s", requestId);
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
                  if (!wasConflicted) {
                    return null;
                  }
                  // The fold is possible again and the tip is the answer, but it is the SAME tip —
                  // nothing new to build. Back to PENDING against what is already there.
                  row.state = ReleaseRequest.State.PENDING;
                  row.detail = "The conflict is resolved; the fold is unchanged";
                  row.mergedSha = outcome.sha();
                  if (ledger.verdictsOf(row.repoId, row.mergedSha).stream()
                      .anyMatch(CommitBuildStatusDto::gating)) {
                    // A gating run has already answered for this exact sha, so evaluate() — which
                    // remerge calls the moment this returns — reads it, and announcing would ask
                    // for a second build of content already built. Same-datasource read, so it
                    // costs this transaction nothing.
                    return null;
                  }
                  // Nothing has EVER built this sha and nothing will: a request that went
                  // CONFLICTED on creation never armed a run, and since the vacuous settle-window
                  // pass went (2026-09-04) no verdict is no release, for ever. "Clears it and
                  // dispatches" is the promise, and this is the half of it that was missing —
                  // superseded is null because nothing moved, so no run is cancelled either.
                  return new Folded(
                      row.id,
                      row.projectId,
                      row.repoId,
                      row.repoName,
                      row.backingBranch(),
                      row.mergedSha,
                      null,
                      now);
                }
                default -> {
                  String superseded = row.mergedSha;
                  boolean moved = !outcome.sha().equals(superseded);
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
                      superseded,
                      now);
                }
              }
            });
  }

  /**
   * Say why a PENDING request is still pending, <b>only when the sentence changed</b>. The sweep
   * re-evaluates every open request every 30 seconds and a request can wait for a whole pipeline,
   * so stamping {@code updatedAt} on every tick would keep re-sorting the board (which is ordered by
   * it) and write a row per request per tick for no news at all.
   */
  private static void waiting(ReleaseRequest row, String detail) {
    if (detail.equals(row.detail)) {
      return;
    }
    row.detail = detail;
    row.updatedAt = Instant.now();
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
    evaluate(id, null);
  }

  /**
   * Re-decide one request's gate.
   *
   * <p><b>The correlation key is the merged sha, and it is checked twice.</b> {@code verdictSha} is
   * the commit a verdict just landed for, or null when nothing in particular prompted this (the
   * sweep, a fold). A verdict naming anything other than the request's <b>current</b> {@code
   * mergedSha} settles nothing and returns here: it is an answer about a fold this request has moved
   * past, and the run that produced it was started for content nobody will accept any more. The
   * ledger rows for it are <b>kept</b> — they are the record of what was built and are read again if
   * that sha ever comes back — and this is also why a re-fold asks qits-ci to cancel the runs it
   * superseded: the gate is already safe without the cancellation, which is why the cancellation is
   * allowed to fail.
   *
   * <p>The verdicts read below are then read <em>at</em> {@code mergedSha} for the same reason, so a
   * request whose fold moved between the two reads is simply evaluated against the newer one.
   */
  void evaluate(String id, String verdictSha) {
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
                  if (verdictSha != null && !verdictSha.equals(row.mergedSha)) {
                    LOG.debugf(
                        "Release request %s ignores a verdict for %s; it is gating %s now",
                        id, shortSha(verdictSha), shortSha(row.mergedSha));
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
                  boolean vouched =
                      verdicts.stream().anyMatch(v -> v.gating() && "SUCCESS".equals(v.status()));
                  if (!vouched) {
                    // THE GATE. Nothing has vouched for this fold, so it does not pass — not after
                    // a window, not because CI looks idle, not ever until a gating run says SUCCESS
                    // for this exact commit. The sweep asks again; a repository whose pipeline never
                    // materializes stays PENDING, which is the correct answer and not a stall.
                    waiting(row, "Waiting for a gating CI verdict for " + shortSha(row.mergedSha));
                    return false;
                  }
                  Integer active =
                      activeBuilds.isResolvable()
                          ? activeBuilds.get().activeFor(row.repoId, row.mergedSha).orElse(null)
                          : null;
                  if (active != null && active > 0) {
                    // Vouched, but qits-ci still has runs on this very fold: a second gating
                    // pipeline can still come back red. Only a POSITIVE count holds — "could not
                    // ask" (no probe, unreachable, unreadable) never overrides the vouch, or a
                    // platform with no probe configured could never release a green commit.
                    waiting(
                        row,
                        active + " CI run(s) are still in flight for " + shortSha(row.mergedSha));
                    return false;
                  }
                  row.state = ReleaseRequest.State.READY;
                  row.detail = null;
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
   * The release itself, on the worker. The row is re-read first, so of two enqueues the second finds
   * a settled request and does nothing; a failure is FAILED with the executor's own words, and
   * whether the sweep retries it is the executor's classification.
   *
   * <p>What is released is the <b>backing branch</b> at the <b>merged sha</b> — the fold, not any
   * one participant — and what the release <em>is</em> is a tag: the executor stamps a calver,
   * rewrites the manifests at the fold, commits them onto the backing branch, tags that commit and
   * deletes the branches the release consumed. {@code main} is finalized after the deployment, which
   * is why the bookkeeping below exists: the tag joins the repository's implicit source set until
   * something merges it, so that every other open request is a superset of what is already shipping.
   *
   * <p>The read below assembles the whole ask — the named sources and the repository's default
   * branch included, because the executor deletes the first and must never delete the second — in
   * <b>one</b> short transaction, and the release happens outside it.
   */
  private void execute(String id) {
    ReleaseExecutor.Release ask =
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
                  return new ReleaseExecutor.Release(
                      row.id,
                      row.repoId,
                      row.projectId,
                      row.repoName,
                      row.backingBranch(),
                      row.mergedSha,
                      row.summary,
                      row.requester,
                      sources.listByRequest(row.id).stream().map(source -> source.name).toList(),
                      mainOf(row.repoId),
                      wrapperCatalogOf(row.repoId, row.projectId));
                });
    if (ask == null) {
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
      outcome = executors.get().release(ask);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not kill the worker.
      LOG.warnf(e, "Release executor threw for request %s", id);
      outcome = ReleaseExecutor.Outcome.refused("executor error: " + e.getMessage());
    }
    if (outcome.released()) {
      // The tag is tracked BEFORE the request is settled, so that a reader who sees RELEASED can
      // rely on the release being in the in-flight set. Only the siblings' re-fold is left after.
      recordReleasedTag(ask.repoId(), id, outcome.version(), outcome.releasedSha());
      settle(id, ReleaseRequest.State.RELEASED, null, outcome.version(), false);
      LOG.infof(
          "Release request %s released %s@%s as %s",
          id,
          ask.repoName() != null ? ask.repoName() : ask.repoId(),
          ask.backingBranch(),
          outcome.version());
      remergeOpenOf(ask.repoId(), id, "the sibling release " + outcome.version() + " is in flight");
      // THE PUBLISH PHASE'S FORK, on the release's own thread and the moment it lands: a repository
      // that declares no deployment has nothing to wait for and its tag goes to main now. One that
      // does deploy is left to DeploymentActive. Last, after the row exists and the request is
      // RELEASED, because the fork reads that row; and never able to fail a release that already
      // happened — a process that dies here leaves an ungated pending row, which is exactly what
      // ReleaseFinalization's catch-up sweep is for.
      finalization.onReleased(ask.repoId(), outcome.version());
      resolveWorkspacesOnReleasedBranches(ask, outcome);
    } else {
      settle(id, ReleaseRequest.State.FAILED, outcome.detail(), null, outcome.retryable());
      LOG.warnf(
          "Release request %s was not released (%s): %s",
          id, outcome.retryable() ? "will retry" : "final until re-armed", outcome.detail());
    }
  }

  /**
   * The branches this release just deleted, told to whoever may have a workspace standing on one.
   *
   * <p><b>Exactly the set the executor deleted</b>, read from the same two fields it reads: the
   * request's named sources <em>minus</em> the repository's default branch, which nothing deletes
   * and no release consumes. The backing branch is deliberately not among them — it is this flow's
   * own scratch ref, created by the fold, and no workspace was ever made on one.
   *
   * <p>Last, after the row is RELEASED and after {@link ReleaseFinalization#onReleased}, and wrapped
   * so that <b>nothing here can reach the release path</b>. The port promises not to throw; the belt
   * is the same one round {@link ReleaseExecutor} above and states the same thing — a throw is a
   * port bug, and a workspace that was not reaped must never turn a release that happened into a
   * failure or settle it a second time. See {@link ReleasedBranchWorkspaces} for the absence this
   * call exists to cover.
   */
  private void resolveWorkspacesOnReleasedBranches(
      ReleaseExecutor.Release ask, ReleaseExecutor.Outcome outcome) {
    if (!releasedBranchWorkspaces.isResolvable()) {
      return;
    }
    try {
      ReleasedBranchWorkspaces workspaces = releasedBranchWorkspaces.get();
      Set<String> released = new LinkedHashSet<>(ask.namedSources());
      released.remove(ask.defaultBranch());
      for (String branch : released) {
        workspaces.branchReleased(
            ask.repoId(), branch, outcome.version(), outcome.releasedSha());
      }
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not touch a settled release.
      LOG.warnf(
          e, "Could not resolve the workspaces of the branches released by request %s",
          ask.requestId());
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
   * How many releases the default reading carries behind the open ones. Ten, because the tail is
   * context and not history: it says what has just landed on the page whose question is what has
   * not, and a number that grew with the repository would turn a worklist back into a log.
   */
  static final int RECENT_RELEASED = 10;

  /**
   * What a {@code state} query selects: a set of states, and whether the recently released tail
   * rides along behind them.
   *
   * <p>The tail is a second query rather than a state in the set, and it has to be: "the last ten
   * RELEASED" is a page, not a predicate, and folding it into the {@code state in (…)} read would
   * answer every release the repository has ever made.
   */
  private record Selection(List<ReleaseRequest.State> states, boolean recentReleased) {}

  /**
   * The states a {@code state} query means, and what else rides with them. <b>Absent or blank is the
   * open set plus the last {@value #RECENT_RELEASED} releases</b> — the question both lists exist to
   * answer is "what is happening here", and a page that dropped a release the moment it landed made
   * the most interesting event in the flow the one thing it never showed. {@code all} is every state
   * (spelled as the full set rather than as "no filter", so one query shape serves both); a state
   * name, in any case, is itself, and narrows to exactly it.
   *
   * <p><b>WITHDRAWN left the default reading when the tail arrived</b>, and that is the intended
   * trade: it was never in the open set, so it only ever appeared on the repository list because
   * that list had no filter at all. It is one {@code state=WITHDRAWN} or one {@code state=all} away.
   */
  private static Selection statesFor(String state) {
    if (state == null || state.isBlank()) {
      return new Selection(ReleaseRequestRepository.OPEN, true);
    }
    String wanted = state.trim();
    if (ALL_STATES.equalsIgnoreCase(wanted)) {
      return new Selection(List.of(ReleaseRequest.State.values()), false);
    }
    for (ReleaseRequest.State candidate : ReleaseRequest.State.values()) {
      if (candidate.name().equalsIgnoreCase(wanted)) {
        return new Selection(List.of(candidate), false);
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
            + "' for every state; leaving it off answers the open ones plus the last "
            + RECENT_RELEASED
            + " released.");
  }

  /**
   * Names a list of rows without a query per row: one read of every named source in the page, one
   * read of each distinct repository's pending tags, and one read of the released tags the page's
   * own requests produced — which is where {@code releasedSha} and {@code mergedToMainAt} both come
   * from.
   *
   * <p>That last read is kept <b>unfiltered</b>: a row whose {@code mergedAt} is still null is the
   * release in flight, and it carries the sha the tag points at just as an already-merged one does.
   * Filtering it out is what used to make the answer say "there is no such release" for exactly the
   * releases somebody is watching.
   */
  private List<ReleaseRequestDto> decorate(
      List<ReleaseRequest> rows, Map<String, String> currentNames) {
    List<String> ids = rows.stream().map(row -> row.id).toList();
    Map<String, List<ReleaseRequestSource>> named =
        sources.listByRequests(ids).stream()
            .collect(Collectors.groupingBy(source -> source.requestId));
    Map<String, List<ReleasedTagPendingMerge>> implicit =
        rows.stream()
            .map(row -> row.repoId)
            .distinct()
            .collect(Collectors.toMap(repoId -> repoId, this::implicitFor));
    Map<String, ReleasedTagPendingMerge> released =
        pendingTags.listByRequests(ids).stream()
            .collect(Collectors.toMap(tag -> tag.releaseRequestId, tag -> tag, (a, b) -> a));
    return rows.stream()
        .map(
            row ->
                dto(
                    row,
                    currentNames.getOrDefault(row.repoId, row.repoName),
                    named.getOrDefault(row.id, List.of()),
                    implicit.getOrDefault(row.repoId, List.of()),
                    released.get(row.id)))
        .toList();
  }

  private List<ReleasedTagPendingMerge> implicitFor(String repoId) {
    return pendingTags.listPending(repoId);
  }

  /**
   * @param released this request's own released tag, or null where it produced none — an unreleased
   *     request, and a release made before {@code released_tag_pending_merge} existed. Both of its
   *     fields on the answer are therefore null together with it.
   */
  private ReleaseRequestDto dto(
      ReleaseRequest row,
      String repoName,
      List<ReleaseRequestSource> named,
      List<ReleasedTagPendingMerge> implicit,
      ReleasedTagPendingMerge released) {
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
        released == null ? null : released.releasedSha,
        released == null ? null : released.mergedAt,
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

  /**
   * The project's other repositories by registered name, and only for a WRAPPER release — the
   * catalog the executor banks the wrapper's gitlink pins from. Empty for every ordinary
   * repository, which is what turns the banking arm off without a flag: an estate of nothing is
   * nothing to pin.
   *
   * <p>The wrapper itself is excluded — a superproject does not pin itself — and a repository the
   * name table has no row for is simply absent, because a gitlink needs the name {@code
   * .gitmodules} declares and an unnamed row cannot be matched to one.
   */
  private Map<String, ReleaseExecutor.Submodule> wrapperCatalogOf(String repoId, String projectId) {
    boolean wrapper =
        repositories
            .findByIdOptional(repoId)
            .map(repository -> repository.archetype == RepositoryArchetype.PROJECT)
            .orElse(false);
    if (!wrapper || projectId == null) {
      return Map.of();
    }
    Map<String, ReleaseExecutor.Submodule> catalog = new LinkedHashMap<>();
    names
        .namesByRepository(projectId)
        .forEach(
            (siblingId, name) -> {
              if (siblingId.equals(repoId)) {
                return;
              }
              catalog.put(name, new ReleaseExecutor.Submodule(siblingId, mainOf(siblingId)));
            });
    return Map.copyOf(catalog);
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
