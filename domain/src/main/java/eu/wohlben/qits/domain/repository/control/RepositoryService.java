package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.InternalServerErrorException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.featureflow.control.FeatureFlowPhaseActionService;
import eu.wohlben.qits.domain.process.control.RepoProcessLease;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.project.entity.Project;
import eu.wohlben.qits.domain.repository.dto.BranchDto;
import eu.wohlben.qits.domain.repository.dto.SyncStatusDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import eu.wohlben.qits.domain.repository.entity.RepositorySubmodule;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.RepositorySubmoduleRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepositoryService {

  private static final Logger LOG = Logger.getLogger(RepositoryService.class);

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject MetadataService metadataService;

  @Inject WorkspaceService workspaceService;

  @Inject ContainerRuntime containerRuntime;

  @Inject GitExecutor git;

  @Inject GitSubmoduleParser submoduleParser;

  @Inject QitsConfigReconciler qitsConfigReconciler;

  @Inject RepositorySubmoduleRepository repositorySubmoduleRepository;

  @Inject FeatureFlowPhaseActionService featureFlowPhaseActionService;

  @Inject TechnicalProcessRegistry processes;

  /**
   * Runs {@link #beginPullRepository}'s recursive pull off the request thread — the HTTP call
   * returns the technical-process id immediately and the browser watches the walk repo by repo over
   * SSE. Mirrors {@code WorkspaceService}'s provision executor.
   */
  private final ExecutorService processExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "repository-pull");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    processExecutor.shutdownNow();
  }

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  /** Clones without importing submodules — the plain primitive (also what child imports use). */
  @Transactional
  public Repository cloneRepository(String url, RepositoryArchetype archetype, Project project) {
    return cloneOne(url, archetype, project, true);
  }

  /**
   * Clones and, when {@code importSubmodules}, imports the repository's DIRECT submodules as
   * sibling repositories (one level — submodule import is user-driven layer by layer; each imported
   * child's own submodules stay unimported until {@link #importDirectSubmodules} is invoked on it,
   * e.g. via the repository detail view's "import submodules" action).
   */
  @Transactional
  public Repository cloneRepository(
      String url, RepositoryArchetype archetype, Project project, boolean importSubmodules) {
    Repository repo = cloneOne(url, archetype, project, true);
    if (importSubmodules) {
      importDirectSubmodules(repo);
    }
    return repo;
  }

  /**
   * Clones one repository under {@code project} — no submodule handling here; that is {@link
   * #importDirectSubmodules}'s job, always explicit and one level at a time. Runs within the
   * caller's transaction.
   *
   * @param createMainWorkspace whether to give the repository its default main-branch workspace —
   *     {@code true} for a user-created repository, {@code false} for an imported child (a
   *     submodule materializes inside its superproject's container, not as an independent sibling
   *     workspace; the child stays usable standalone since {@code createMainWorkspace} is
   *     idempotent).
   */
  private Repository cloneOne(
      String url, RepositoryArchetype archetype, Project project, boolean createMainWorkspace) {
    if (url == null || url.isBlank()) {
      throw new BadRequestException("url is required");
    }
    String trimmedUrl = url.trim();
    // `url` is user-supplied and passed to `git clone`. Reject a dash-leading value so it can't be
    // smuggled in as a flag (argv flag injection), and the `ext::` transport which lets a remote
    // run
    // arbitrary commands. Local paths and https/ssh/git remotes are all still allowed.
    if (trimmedUrl.startsWith("-") || trimmedUrl.regionMatches(true, 0, "ext::", 0, 5)) {
      throw new BadRequestException("Invalid repository URL: " + trimmedUrl);
    }

    Repository repo = new Repository();
    repo.id = UUID.randomUUID().toString();
    repo.url = trimmedUrl;
    repo.archetype = archetype != null ? archetype : RepositoryArchetype.SERVICE;
    repo.project = project;
    repositoryRepository.persist(repo);

    Path originPath = Path.of(dataDir, repo.id, "origin");
    try {
      Files.createDirectories(originPath.getParent());
      git.exec(
          null, "git", "clone", "--mirror", "--end-of-options", repo.url, originPath.toString());
    } catch (Exception e) {
      throw new InternalServerErrorException("Git clone failed: " + e.getMessage());
    }

    // The main branch defaults to the remote's default branch (the mirror's HEAD).
    repo.mainBranch = detectDefaultBranch(originPath);

    // Ingest the committed .qits-config.yml (if any) before the main workspace is created, so the
    // workspace lands on the possibly-config-overridden main branch, and before metadata is written
    // so it captures the final branch/archetype. Never fails the clone (degrade loudly, never
    // block).
    ingestConfigQuietly(repo.id);

    metadataService.writeRepositoryMetadata(repo);

    // Every repository starts with a default workspace checked out on its main branch, so the main
    // branch is immediately workable and appears as a workspace-backed root in the branch tree.
    // Suppressed for imported children — they materialize inside their superproject's container.
    if (createMainWorkspace) {
      workspaceService.createMainWorkspace(repo.id, repo.mainBranch);
    }

    return repo;
  }

  /**
   * Imports {@code repoId}'s DIRECT {@code .gitmodules} submodules as sibling repositories under
   * the same project, each linked by a {@link RepositorySubmodule} edge, and returns the
   * repository's full edge list afterwards. One level only, by design: submodule import is
   * user-driven layer by layer — a "want it deeper" recursion is the user invoking this on the
   * imported child (the repository detail view's "import submodules" action), as far down as they
   * care to go. Idempotent: children dedup by resolved url within the project, edges by (parent,
   * path), so re-invoking imports only what's missing. Cycles need no special guard anymore — a
   * cyclic pair simply links back to the already-imported row on the second, user-invoked step.
   */
  @Transactional
  public List<RepositorySubmodule> importDirectSubmodules(String repoId) {
    Repository repo = get(repoId);
    importDirectSubmodules(repo);
    return repositorySubmoduleRepository.findByParentId(repoId);
  }

  private void importDirectSubmodules(Repository repo) {
    Path originPath = originPath(repo.id);
    for (GitSubmoduleParser.Submodule sub :
        submoduleParser.readSubmodules(originPath.toFile(), repo.mainBranch)) {
      String childUrl = submoduleParser.resolveSubmoduleUrl(repo.url, sub.url());

      // Dedup within the project: reuse an existing sibling with the same url (the diamond case —
      // and what terminates a cyclic pair: its second import finds the first repo already there).
      // Panache auto-flushes before this query, so a child imported earlier in this same
      // transaction is already visible.
      Repository child =
          repositoryRepository.findByUrlInProject(childUrl, repo.project.id).orElse(null);
      if (child == null) {
        child = cloneOne(childUrl, RepositoryArchetype.SERVICE, repo.project, false);
      }

      if (!repositorySubmoduleRepository.existsByParentAndPath(repo.id, sub.path())) {
        RepositorySubmodule edge = new RepositorySubmodule();
        edge.parent = repo;
        edge.child = child;
        edge.path = sub.path();
        edge.name = sub.name();
        repositorySubmoduleRepository.persist(edge);
      }
    }
  }

  /**
   * The DIRECT {@code .gitmodules} submodules of {@code repoId} that are not yet imported (no edge
   * at their path) — what the repository detail view's "import submodules" action offers. Urls come
   * back resolved (relative names folded against the repository's own url).
   */
  public List<GitSubmoduleParser.Submodule> listUnimportedSubmodules(String repoId) {
    Repository repo = get(repoId);
    Set<String> importedPaths =
        repositorySubmoduleRepository.findByParentId(repoId).stream()
            .map(edge -> edge.path)
            .collect(java.util.stream.Collectors.toSet());
    return submoduleParser.readSubmodules(originPath(repo.id).toFile(), repo.mainBranch).stream()
        .filter(sub -> !importedPaths.contains(sub.path()))
        .map(
            sub ->
                new GitSubmoduleParser.Submodule(
                    sub.name(),
                    sub.path(),
                    submoduleParser.resolveSubmoduleUrl(repo.url, sub.url()),
                    sub.branch()))
        .toList();
  }

  private Path originPath(String repoId) {
    return Path.of(dataDir, repoId, "origin");
  }

  /**
   * Reconciles the repository's {@code .qits-config.yml} without ever letting a config problem
   * break the clone/sync. The reconciler already records parse/validation issues as a repository
   * warning rather than throwing; this only guards against an unexpected failure, matching the
   * "degrade loudly, never block" posture of framework detection and submodule import.
   */
  private void ingestConfigQuietly(String repoId) {
    try {
      qitsConfigReconciler.ingest(repoId);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Failed to ingest .qits-config.yml for repository %s", repoId);
    }
  }

  /** The mirror's HEAD points at the remote's default branch (e.g. "master"/"main"). */
  private String detectDefaultBranch(Path originPath) {
    try {
      return git.exec(originPath.toFile(), "git", "symbolic-ref", "--short", "HEAD").trim();
    } catch (Exception e) {
      return "master";
    }
  }

  /** The configured main branch, falling back to the remote's default branch. */
  private String resolveMainBranch(Repository repo, Path originPath) {
    if (repo.mainBranch != null && !repo.mainBranch.isBlank()) {
      return repo.mainBranch;
    }
    return detectDefaultBranch(originPath);
  }

  /**
   * Pulls the remote's main branch into the local mirror. Fetches the branch and fast-forwards the
   * local ref only when the remote is strictly ahead; a no-op when already up to date or locally
   * ahead, and an error when the histories have diverged (manual merge needed). After its own pull,
   * recursively pulls the repository's IMPORTED submodule children (sibling repositories) — a
   * gitlink bump arriving on the superproject's main branch must never point at a commit the child
   * sibling's origin does not yet have, or the workspace container's {@code submodule update}
   * (which clones from that origin via the git host) fails with "Server does not allow request for
   * unadvertised object".
   */
  public String pullRepository(String repoId) {
    return pullRepository(repoId, new HashSet<>(), null, null, new HashSet<>());
  }

  /**
   * The streamed pull: registers a repository-scoped {@link TechnicalProcess} <em>before</em> any
   * git runs (so the currently-fetching repo is visible while its {@code git fetch} blocks on the
   * network), runs the recursive walk on a worker thread, and returns the process id immediately.
   * The browser watches the walk repo by repo over the process's SSE stream — one segment per
   * pulled repository; failures surface there (live, untruncated), not as an HTTP error. Throws 404
   * in-request when the repository doesn't exist, so a bad id still fails fast. Mirrors {@code
   * WorkspaceService.beginEnsureContainer}.
   *
   * <p>Kind-aware single-flight (see {@link TechnicalProcessRegistry#beginForRepository}): a live
   * pull for this repo is reused (its id is returned, no second walk — two walks race the bare
   * origin's ref-locks); a live <em>sync</em> is a conflict (a pull can't ride a sync's push
   * semantics), rejected with a 400. This closes the race even for a client that never learned a
   * pull was running (dialog closed, button clicked again).
   */
  public String beginPullRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and name the root segment by the
    // repo's url basename — Repository has no display name; this is the identity the WARNING lines
    // (and reposByName in tests) already use.
    String rootSegment =
        QuarkusTransaction.requiringNew()
            .call(() -> "pull:" + Path.of(get(repoId).url).getFileName().toString());
    return switch (processes.beginForRepository(repoId, "pull")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        // Segment names double as the segment key, so they must be unique across the whole walk:
        // two
        // repos reached under the same relative path (nested levels, or a child path equal to the
        // root basename) would otherwise collide and a failed one's verdict would be swallowed by
        // the first's `ok`. Threaded through the recursion, this allocator disambiguates a repeat
        // with a suffix.
        Set<String> usedSegments = new HashSet<>();
        usedSegments.add(rootSegment);
        processExecutor.submit(
            () -> {
              try {
                pullRepository(repoId, new HashSet<>(), process, rootSegment, usedSegments);
                // No asynchronous second phase: declare an empty daemon set and settle the
                // provision
                // so `done` fires immediately. A child segment settled `failed` still makes
                // finish()
                // compute overall `done failed`.
                process.expectDaemons(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                // Root failure (diverged branch, unreachable remote): settle the open root segment
                // failed (appending the message) and emit `done failed`. Idempotent.
                process.failProvision(e.getMessage());
                LOG.debugf(e, "Streamed pull failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /**
   * A pull and a sync can't share a walk (a pull would skip the push) nor safely run concurrently
   * against the same bare origin, so a cross-kind request while one is live is rejected. In
   * practice the frontend guard disables the buttons while any repo process is live, so this only
   * ever fires for a second tab / API client that hasn't yet learned a process is running.
   */
  private BadRequestException repositoryBusy(String runningKind) {
    return new BadRequestException(
        "A " + runningKind + " is already running for this repository; wait for it to finish.");
  }

  /** The scalar snapshot a single repo's pull needs, read in one short transaction. */
  private record PullContext(String url, String branch, Path workdir, boolean hasMainWorkspace) {}

  /** A submodule edge flattened to scalars, so it outlives the transaction that loaded it. */
  private record ChildEdge(String path, String childId, String childUrl) {}

  /**
   * {@link #pullRepository(String)} with the recursion state over the imported submodule edge graph
   * and an optional {@link TechnicalProcess} sink: {@code visited} both terminates cycles (the
   * {@code submodule-cycle-*} pair) and dedups the diamond (a shared child is pulled once per
   * invocation — so it gets exactly one segment, under the first edge that reached it, and a cycle
   * never reopens one). With a process, this repo's own pull is streamed as {@code segmentName}
   * (opened at entry, settled when it completes); {@code process}/{@code segmentName} are null for
   * the synchronous callers ({@code syncRepository}), leaving them untouched.
   *
   * <p>Runs on a worker thread when streaming, so every DB touch opens its own transaction.
   */
  private String pullRepository(
      String repoId,
      Set<String> visited,
      TechnicalProcess process,
      String segmentName,
      Set<String> usedSegments) {
    if (!visited.add(repoId)) {
      return "";
    }
    if (process != null && segmentName != null) {
      process.openSegment(segmentName);
    }

    // The main branch lives in its default workspace, so pull there: git refuses to fetch-update a
    // ref that is checked out, and updating it behind the workspace's back would desync the working
    // tree. We fetch by URL (into FETCH_HEAD, not refs/heads/*) and fast-forward the workspace,
    // which moves the ref and the checkout together. Fall back to the bare origin for a repo with
    // no main workspace.
    PullContext ctx =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = get(repoId);
                  Path originPath = requireOrigin(repoId);
                  String branch = resolveMainBranch(repo, originPath);
                  Optional<Path> mainWorkspace =
                      workspaceService.workspacePathForBranch(repoId, branch);
                  return new PullContext(
                      repo.url,
                      branch,
                      mainWorkspace.orElse(originPath),
                      mainWorkspace.isPresent());
                });

    try {
      // `--end-of-options`: url and branch are positional, never parsed as flags, so neither a
      // dash-leading url (already rejected at clone) nor branch can smuggle a git flag.
      // Stream the fetch line by line into the segment (live progress on a slow fetch; every line
      // stamps the process's activity clock so a long-but-active fetch can't trip the idle reaper).
      // The other pull verbs below are single-line and stay post-hoc via streamLine.
      String fetchOutput =
          git.exec(
              ctx.workdir().toFile(),
              lineSink(process, segmentName),
              "git",
              "fetch",
              "--end-of-options",
              ctx.url(),
              ctx.branch());
      String remoteSha = git.exec(ctx.workdir().toFile(), "git", "rev-parse", "FETCH_HEAD").trim();
      String localSha =
          git.exec(ctx.workdir().toFile(), "git", "rev-parse", "refs/heads/" + ctx.branch()).trim();

      if (remoteSha.equals(localSha) || isAncestor(ctx.workdir(), remoteSha, localSha)) {
        // Already up to date, or local is ahead — nothing to pull; children may still be stale.
        streamLine(
            process,
            segmentName,
            remoteSha.equals(localSha) ? "Already up to date" : "Local branch is ahead of remote");
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      if (isAncestor(ctx.workdir(), localSha, remoteSha)) {
        // Remote is strictly ahead — fast-forward.
        if (ctx.hasMainWorkspace()) {
          // Update the ref and the working tree together (the branch is checked out here).
          git.exec(
              ctx.workdir().toFile(), "git", "merge", "--ff-only", "--end-of-options", remoteSha);
        } else {
          git.exec(
              ctx.workdir().toFile(), "git", "update-ref", "refs/heads/" + ctx.branch(), remoteSha);
        }
        streamLine(process, segmentName, "Fast-forwarded to " + shortSha(remoteSha));
        // The main branch advanced — re-ingest .qits-config.yml from its new tip in the bare
        // origin.
        ingestConfigQuietly(repoId);
        streamConfigOutcome(process, segmentName, repoId);
        settleOk(process, segmentName);
        return withImportedChildPulls(repoId, fetchOutput, visited, process, usedSegments);
      }
      throw new BadRequestException(
          "Branch '" + ctx.branch() + "' has diverged from the remote; manual merge required");
    } catch (BadRequestException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git pull failed: " + e.getMessage());
    }
  }

  /**
   * Pulls each imported submodule child after {@code repoId}'s own successful pull, appending their
   * outputs. A child failure (diverged, unreachable remote) degrades loudly, never blocks: the
   * superproject's pull already succeeded, so the child's error becomes a WARNING line in the
   * returned output (for the synchronous callers) and, when streaming, settles the child's segment
   * {@code failed} while the walk continues to the remaining children.
   */
  private String withImportedChildPulls(
      String repoId,
      String ownOutput,
      Set<String> visited,
      TechnicalProcess process,
      Set<String> usedSegments) {
    List<ChildEdge> edges =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    repositorySubmoduleRepository.findByParentId(repoId).stream()
                        .map(e -> new ChildEdge(e.path, e.child.id, e.child.url))
                        .toList());
    StringBuilder output = new StringBuilder(ownOutput.trim());
    for (ChildEdge edge : edges) {
      String childSegment = allocateSegment("pull:" + edge.path(), usedSegments);
      try {
        String childOutput =
            pullRepository(edge.childId(), visited, process, childSegment, usedSegments).trim();
        if (!childOutput.isBlank()) {
          output.append('\n').append(childOutput);
        }
      } catch (Exception e) {
        LOG.warnf(
            e, "Pull of imported submodule '%s' of repository %s failed", edge.path(), repoId);
        output
            .append("\nWARNING: pull of imported submodule '")
            .append(edge.path())
            .append("' (")
            .append(edge.childUrl())
            .append(") failed: ")
            .append(e.getMessage());
        if (process != null) {
          process.appendLine(childSegment, "pull failed: " + e.getMessage());
          process.settleSegment(childSegment, false);
        }
      }
    }
    return output.toString();
  }

  private static String shortSha(String sha) {
    return sha.length() > 12 ? sha.substring(0, 12) : sha;
  }

  /**
   * A segment name for {@code base} unique within {@code usedSegments} (registering it): the plain
   * base, or {@code base (2)}, {@code base (3)}, … when two repos in one walk share a relative
   * path.
   */
  private static String allocateSegment(String base, Set<String> usedSegments) {
    String name = base;
    for (int n = 2; !usedSegments.add(name); n++) {
      name = base + " (" + n + ")";
    }
    return name;
  }

  private static void settleOk(TechnicalProcess process, String segmentName) {
    if (process != null && segmentName != null) {
      process.settleSegment(segmentName, true);
    }
  }

  private static void streamLine(TechnicalProcess process, String segmentName, String line) {
    if (process != null && segmentName != null) {
      process.appendLine(segmentName, line);
    }
  }

  /**
   * A per-line tap that appends each line to the segment as a git command emits it (for {@link
   * GitExecutor#exec(java.io.File, Consumer, String...)}), or {@code null} when there is no process
   * to stream into — so the synchronous callers keep the plain blocking exec.
   */
  private static Consumer<String> lineSink(TechnicalProcess process, String segmentName) {
    return (process == null || segmentName == null)
        ? null
        : line -> process.appendLine(segmentName, line);
  }

  /**
   * Splits captured git output into lines and appends them to the segment (a no-op sans process).
   */
  private static void streamLines(TechnicalProcess process, String segmentName, String output) {
    if (process == null || segmentName == null || output == null || output.isBlank()) {
      return;
    }
    // Default limit drops trailing empty lines (git output usually ends in a newline) while keeping
    // interior blank lines, so a fetch blob doesn't add a stray blank line to the segment.
    for (String line : output.split("\n")) {
      process.appendLine(segmentName, line);
    }
  }

  /**
   * Reports the .qits-config.yml re-ingestion outcome into the segment after a main-branch advance.
   */
  private void streamConfigOutcome(TechnicalProcess process, String segmentName, String repoId) {
    if (process == null || segmentName == null) {
      return;
    }
    String warning = QuarkusTransaction.requiringNew().call(() -> get(repoId).configWarning);
    if (warning == null || warning.isBlank()) {
      process.appendLine(segmentName, "Re-ingested .qits-config.yml");
    } else {
      process.appendLine(segmentName, "Re-ingested .qits-config.yml with warnings:");
      streamLines(process, segmentName, warning);
    }
  }

  /** The scalar snapshot a push needs, read in one short transaction. */
  private record PushContext(String url, String branch, Path originPath) {}

  /**
   * Pushes the local main branch to the remote. Pushes to the URL directly rather than the "origin"
   * remote, whose {@code mirror=true} config forbids the single-branch refspec.
   *
   * <p>Reads its inputs in a short transaction and runs {@code git push} outside it (the pull's
   * {@link PullContext} pattern), so it is safe on a worker thread with no request context — the
   * streamed sync ({@link #beginSyncRepository}) calls it there.
   */
  public String pushRepository(String repoId) {
    PushContext ctx =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = get(repoId);
                  Path originPath = requireOrigin(repoId);
                  return new PushContext(repo.url, resolveMainBranch(repo, originPath), originPath);
                });
    try {
      return git.exec(
          ctx.originPath().toFile(),
          "git",
          "push",
          ctx.url(),
          "refs/heads/" + ctx.branch() + ":refs/heads/" + ctx.branch());
    } catch (Exception e) {
      throw new InternalServerErrorException("Git push failed: " + e.getMessage());
    }
  }

  /**
   * Pull then push the main branch, synchronously. Kept as the throwing, request-thread variant for
   * internal callers; the browser-facing sync endpoint uses the streamed {@link
   * #beginSyncRepository}.
   */
  public String syncRepository(String repoId) {
    String pullOutput = pullRepository(repoId);
    String pushOutput = pushRepository(repoId);
    return (pullOutput + "\n" + pushOutput).trim();
  }

  /**
   * The streamed sync: the {@link #beginPullRepository} walk (one {@code pull:<repo>} segment per
   * repository) followed by a single {@code push:<basename>} segment wrapping {@link
   * #pushRepository}. Registers the {@link TechnicalProcess} before any git runs and returns its id
   * immediately; the browser watches pull-then-push over SSE. Throws 404 in-request for an unknown
   * id.
   *
   * <p>Failure semantics carry over: a diverged/unreachable pull fails the process before the push
   * segment opens ({@link TechnicalProcess#failProvision}); a push failure settles only the {@code
   * push} segment {@code failed} and lets {@code finish()} compute overall {@code done failed}, so
   * a green pull with a red push reads exactly that way.
   *
   * <p>Kind-aware single-flight (see {@link #beginPullRepository}): a live sync is reused; a live
   * <em>pull</em> is a conflict (attaching a sync to a pull would silently skip the push), rejected
   * with a 400 rather than letting a sync report success without pushing.
   */
  public String beginSyncRepository(String repoId) {
    // Validate in-request (unknown id → plain 404, not a process) and derive the url basename
    // shared
    // by the root pull segment and the final push segment.
    String basename =
        QuarkusTransaction.requiringNew()
            .call(() -> Path.of(get(repoId).url).getFileName().toString());
    return switch (processes.beginForRepository(repoId, "sync")) {
      case RepoProcessLease.Reused r -> r.processId();
      case RepoProcessLease.Conflict c -> throw repositoryBusy(c.runningKind());
      case RepoProcessLease.Fresh f -> {
        TechnicalProcess process = f.process();
        String rootSegment = "pull:" + basename;
        // Segment names double as the segment key: seed the allocator with the root name so the
        // push
        // segment (and any child pull) can't collide with it.
        Set<String> usedSegments = new HashSet<>();
        usedSegments.add(rootSegment);
        processExecutor.submit(
            () -> {
              try {
                pullRepository(repoId, new HashSet<>(), process, rootSegment, usedSegments);
                // Pull walk done — append the push as its own segment, opened before the push so
                // "now pushing" is visible while it blocks on the network.
                String pushSegment = allocateSegment("push:" + basename, usedSegments);
                process.openSegment(pushSegment);
                try {
                  streamLines(process, pushSegment, pushRepository(repoId));
                  settleOk(process, pushSegment);
                } catch (RuntimeException e) {
                  // A push failure degrades this segment only (not failProvision): the pull
                  // segments
                  // stay green and finish() computes overall `done failed` from the red push
                  // segment.
                  process.appendLine(pushSegment, "push failed: " + e.getMessage());
                  process.settleSegment(pushSegment, false);
                }
                process.expectDaemons(List.of());
                process.finishProvision(true);
              } catch (RuntimeException e) {
                // Root pull failure (diverged branch, unreachable remote): settle the open pull
                // segment failed and emit `done failed` before the push segment ever opens.
                // Idempotent.
                process.failProvision(e.getMessage());
                LOG.debugf(e, "Streamed sync failed for repository %s", repoId);
              }
            });
        yield process.id();
      }
    };
  }

  /** Sets the branch this repository syncs with the remote. The branch must exist locally. */
  @Transactional
  public Repository setMainBranch(String repoId, String branch) {
    Repository repo = get(repoId);
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("branch is required");
    }
    if (!listBranches(repoId).contains(branch)) {
      throw new BadRequestException("Unknown branch: " + branch);
    }
    repo.mainBranch = branch;
    return repo;
  }

  /**
   * Reports how far the main branch is ahead of / behind the remote, using a read-only {@code git
   * ls-remote} (no objects fetched). Degrades gracefully when the remote is unreachable.
   */
  public SyncStatusDto syncStatus(String repoId) {
    Repository repo = get(repoId);
    Path originPath = requireOrigin(repoId);
    String branch = resolveMainBranch(repo, originPath);

    String localSha;
    try {
      localSha =
          git.exec(originPath.toFile(), "git", "rev-parse", "--verify", "refs/heads/" + branch)
              .trim();
    } catch (Exception e) {
      // The main branch doesn't exist locally — treat as nothing to report.
      return new SyncStatusDto(branch, true, false, null, null);
    }

    String remoteSha;
    try {
      String out =
          git.exec(originPath.toFile(), "git", "ls-remote", "origin", "refs/heads/" + branch)
              .trim();
      remoteSha = out.isBlank() ? null : out.split("\\s+")[0];
    } catch (Exception e) {
      return new SyncStatusDto(branch, false, false, null, null);
    }

    if (remoteSha == null) {
      return new SyncStatusDto(branch, true, false, null, null);
    }
    if (remoteSha.equals(localSha)) {
      return new SyncStatusDto(branch, true, true, 0, 0);
    }

    // The histories differ. Counting needs the remote commits in the mirror's object store, so
    // fetch them first. Fetch by URL rather than via the "origin" remote: origin is a --mirror,
    // so `git fetch origin` would fast-forward refs/heads/* (a de-facto pull). Fetching the URL
    // populates the objects and FETCH_HEAD while leaving the mirror's branch refs untouched —
    // the same reason pushRepository talks to the URL instead of the mirror remote.
    Integer ahead = null;
    Integer behind = null;
    try {
      // `--end-of-options` forces the URL and refspec to be read as operands, and the
      // `refs/heads/` prefix means a crafted branch name can never start with `-`, so neither
      // can smuggle a git flag (e.g. `--upload-pack=<cmd>`) into the fetch.
      git.exec(
          originPath.toFile(),
          "git",
          "fetch",
          "--end-of-options",
          repo.url,
          "refs/heads/" + branch);
      String counts =
          git.exec(
                  originPath.toFile(),
                  "git",
                  "rev-list",
                  "--left-right",
                  "--count",
                  remoteSha + "..." + localSha)
              .trim();
      String[] parts = counts.split("\\s+");
      if (parts.length == 2) {
        behind = Integer.parseInt(parts[0]);
        ahead = Integer.parseInt(parts[1]);
      }
    } catch (Exception ignored) {
      // Fetch failed or counts unavailable — leave them null (the UI shows "unknown", not in-sync).
    }
    return new SyncStatusDto(branch, true, true, ahead, behind);
  }

  private Path requireOrigin(String repoId) {
    Path originPath = originPath(repoId);
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }
    return originPath;
  }

  /** True when {@code maybeAncestor} is an ancestor of {@code descendant} (or they are equal). */
  private boolean isAncestor(Path originPath, String maybeAncestor, String descendant) {
    try {
      git.exec(
          originPath.toFile(), "git", "merge-base", "--is-ancestor", maybeAncestor, descendant);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public Repository get(String repoId) {
    return repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
  }

  /** The submodule edges whose superproject is {@code repoId} (its imported child repositories). */
  public List<RepositorySubmodule> listSubmodules(String repoId) {
    get(repoId); // verify the repository exists
    return repositorySubmoduleRepository.findByParentId(repoId);
  }

  public List<String> listBranches(String repoId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    try {
      String output = git.exec(originPath.toFile(), "git", "branch", "--format=%(refname:short)");
      return output.lines().map(String::trim).filter(b -> !b.isBlank()).toList();
    } catch (Exception e) {
      throw new InternalServerErrorException("Git branch listing failed: " + e.getMessage());
    }
  }

  /**
   * The repository's branches, each tagged with whether it can be safely cleaned up (see {@link
   * WorkspaceService#canCleanupBranch}). Used by the branch list UI to offer cleanup in place of
   * integrate once a branch is fully merged.
   */
  public List<BranchDto> listBranchesWithCleanup(String repoId) {
    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    return listBranches(repoId).stream()
        .map(
            b -> {
              var summary = workspaceService.summarize(repoId, originPath, b, repo.mainBranch);
              return new BranchDto(
                  b,
                  workspaceService.canCleanupBranch(repoId, originPath, b, repo.mainBranch),
                  summary.parent(),
                  summary.ahead(),
                  summary.behind());
            })
        .toList();
  }

  /**
   * Deletes a git branch from the repository's origin. Refuses to delete a branch that is the
   * {@code parent} of any workspace, since that would orphan those workspaces in the branch tree.
   */
  @Transactional
  public void deleteBranch(String repoId, String branch) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    // `branch` is user-supplied: reject blank or dash-leading names so a value like
    // "-D"/"--force" can't be smuggled to git as a flag (argv flag injection).
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name: " + branch);
    }

    boolean hasChildren =
        workspaceRepository.findActiveByRepositoryId(repoId).stream()
            .anyMatch(wt -> branch.equals(wt.parent));
    if (hasChildren) {
      throw new BadRequestException("Branch has child workspaces: " + branch);
    }

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    try {
      // `--` terminates option parsing so the branch name is always treated as a ref.
      git.exec(originPath.toFile(), "git", "branch", "-D", "--", branch);
    } catch (Exception e) {
      throw new InternalServerErrorException("Git branch delete failed: " + e.getMessage());
    }
  }

  @Transactional
  public void delete(String repoId) {
    Repository repo = get(repoId);
    // Delete the whole footprint, not just the DB row: otherwise every delete (and every seed
    // reset, which deletes then recreates) leaks the repo's workspace containers and its on-disk
    // clone directory as orphans. DB rows for workspaces/commands/events/daemons cascade off the
    // repository row deletion below.
    for (ContainerRuntime.ContainerInfo info : containerRuntime.listWorkspaceContainers(repoId)) {
      try {
        containerRuntime.rm(info.name());
      } catch (RuntimeException e) {
        LOG.warnf(
            "Failed to remove container %s while deleting repository %s: %s",
            info.name(), repoId, e.getMessage());
      }
    }
    deleteDataDir(repoId);
    // The repository row cascade-deletes its own actions; unbind them from any feature flow first
    // (the phase-action FK has no cascade, so a still-bound action would fail the delete).
    featureFlowPhaseActionService.deleteBindingsForRepository(repoId);
    repositoryRepository.delete(repo);
  }

  /** Recursively remove {@code <data-dir>/<repoId>} (bare origin + any transient merge scratch). */
  private void deleteDataDir(String repoId) {
    Path repoDir = Path.of(dataDir, repoId);
    if (!Files.exists(repoDir)) {
      return;
    }
    try (var paths = Files.walk(repoDir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  LOG.warnf("Failed to delete %s: %s", p, e.getMessage());
                }
              });
    } catch (IOException e) {
      LOG.warnf("Failed to remove data dir for repository %s: %s", repoId, e.getMessage());
    }
  }
}
